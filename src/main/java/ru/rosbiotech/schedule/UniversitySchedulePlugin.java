
package ru.rosbiotech.schedule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class UniversitySchedulePlugin extends JavaPlugin {

  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("HH:mm");

  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private final Map<Integer, Lesson> lessons = new ConcurrentHashMap<>();
  private final Map<Integer, String> previousSnapshot =
      new ConcurrentHashMap<>();
  private final List<String> changeHistory = new ArrayList<>();

  private volatile boolean loaded;
  private volatile boolean refreshRunning;
  private BukkitTask refreshTask;

  private ZoneId zone;
  private int groupId;
  private int maxLessonsPerDay;
  private boolean useFullTeacherName;
  private boolean useApiColors;
  private boolean timeBackground;
  private boolean replacementBackground;
  private boolean notificationsEnabled;
  private boolean notifyOnFirstLoad;
  private boolean notifyOnChanges;
  private int maxHistory;
  private LocalDate semesterStart;
  private LocalDate semesterEnd;
  private TextColor defaultColor;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    loadSettings();

    getLogger().info("UniversitySchedule включается для группы " + groupId);
    refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
        this, this::refreshSchedule, 1L,
        Math.max(60,
                 getConfig().getLong("api.refresh-interval-seconds", 300L)) *
            20L);
  }

  @Override
  public void onDisable() {
    if (refreshTask != null) {
      refreshTask.cancel();
    }
    getLogger().info("UniversitySchedule выключен.");
  }

  private void loadSettings() {
    FileConfiguration c = getConfig();

    groupId = c.getInt("api.group-id", 18513);
    maxLessonsPerDay = Math.max(1, c.getInt("display.max-lessons-per-day", 12));
    useFullTeacherName = c.getBoolean("display.use-full-teacher-name", false);
    useApiColors = c.getBoolean("display.use-api-colors", true);
    timeBackground = c.getBoolean("display.time-background", true);
    replacementBackground =
        c.getBoolean("display.replacement-background", true);

    notificationsEnabled = c.getBoolean("notifications.enabled", true);
    notifyOnFirstLoad =
        c.getBoolean("notifications.notify-on-first-load", false);
    notifyOnChanges = c.getBoolean("notifications.notify-on-changes", true);
    maxHistory = Math.max(1, c.getInt("notifications.max-history", 50));

    zone = ZoneId.of(c.getString("display.timezone", "Europe/Moscow"));
    semesterStart =
        LocalDate.parse(c.getString("semester.start-date", "2026-09-01"));
    semesterEnd =
        LocalDate.parse(c.getString("semester.end-date", "2027-01-25"));

    if (semesterEnd.isBefore(semesterStart)) {
      throw new IllegalArgumentException(
          "semester.end-date не может быть раньше semester.start-date");
    }

    defaultColor = parseColor(c.getString("display.default-color", "#607D8B"),
                              TextColor.color(0x607D8B));
  }

  private void refreshSchedule() {
    if (refreshRunning) {
      return;
    }

    refreshRunning = true;
    try {
      String template = getConfig().getString(
          "api.url", "https://dekanat.rosbiotech.ru/api/Rasp?idGroup=%group%");
      String url = template.replace("%group%", Integer.toString(groupId));

      HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .timeout(Duration.ofSeconds(getConfig().getLong(
                                    "api.request-timeout-seconds", 15)))
                                .header("Accept", "application/json")
                                .header("User-Agent", "UniversitySchedule/1.0")
                                .GET()
                                .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        getLogger().warning("API вернул HTTP " + response.statusCode());
        return;
      }

      JsonObject root =
          JsonParser.parseString(response.body()).getAsJsonObject();
      JsonObject data = root.has("data") && root.get("data").isJsonObject()
                            ? root.getAsJsonObject("data")
                            : root;

      if (!data.has("rasp") || !data.get("rasp").isJsonArray()) {
        getLogger().warning("API не содержит массив data.rasp");
        return;
      }

      JsonArray array = data.getAsJsonArray("rasp");
      Map<Integer, Lesson> fresh = new LinkedHashMap<>();
      Map<Integer, String> freshSnapshot = new HashMap<>();

      for (JsonElement element : array) {
        if (!element.isJsonObject()) {
          continue;
        }

        Lesson lesson = parseLesson(element.getAsJsonObject());
        if (lesson == null) {
          continue;
        }

        fresh.put(lesson.id, lesson);
        freshSnapshot.put(lesson.id, lesson.signature());
      }

      Map<Integer, Lesson> oldLessons = new HashMap<>(lessons);
      Map<Integer, String> oldSnapshot = new HashMap<>(previousSnapshot);
      boolean firstLoad = !loaded;

      List<String> changes =
          firstLoad
              ? List.of()
              : calculateChanges(oldLessons, oldSnapshot, fresh, freshSnapshot);

      lessons.clear();
      lessons.putAll(fresh);
      previousSnapshot.clear();
      previousSnapshot.putAll(freshSnapshot);
      loaded = true;

      Bukkit.getScheduler().runTask(this, () -> {
        if (firstLoad && notifyOnFirstLoad) {
          broadcast(Component.text("Расписание загружено: " + fresh.size() +
                                       " занятий.",
                                   NamedTextColor.GREEN));
        }

        if (notificationsEnabled && notifyOnChanges) {
          for (String change : changes) {
            rememberChange(change);
            broadcast(
                Component.text("[Расписание] ", NamedTextColor.GOLD)
                    .append(Component.text(change, NamedTextColor.YELLOW)));
          }
        }

        getLogger().info("Расписание обновлено: " + fresh.size() + " занятий.");
      });

    } catch (Exception e) {
      getLogger().warning("Ошибка обновления расписания: " + e.getMessage());
    } finally {
      refreshRunning = false;
    }
  }

  private Lesson parseLesson(JsonObject o) {
    try {
      int id = getInt(o, "код", -1);
      if (id < 0) {
        return null;
      }

      String startRaw = getString(o, "датаНачала", "");
      String endRaw = getString(o, "датаОкончания", "");
      if (startRaw.isBlank() || endRaw.isBlank()) {
        return null;
      }

      LocalDateTime start = LocalDateTime.parse(startRaw);
      LocalDateTime end = LocalDateTime.parse(endRaw);

      String subject = getString(o, "дисциплина", "Без названия");
      String teacher = getString(o, "фиоПреподавателя", "");
      if (useFullTeacherName || teacher.isBlank()) {
        teacher = getString(o, "преподаватель", teacher);
      }

      String room = getString(o, "аудитория", "Не указана");
      String color = getString(o, "цвет", "");
      boolean replacement = getBoolean(o, "замена", false);

      return new Lesson(id, start, end, subject, teacher, room, color,
                        replacement);
    } catch (Exception e) {
      getLogger().warning("Не удалось разобрать занятие: " + e.getMessage());
      return null;
    }
  }

  private List<String> calculateChanges(Map<Integer, Lesson> oldLessons,
                                        Map<Integer, String> oldSnapshot,
                                        Map<Integer, Lesson> fresh,
                                        Map<Integer, String> freshSnapshot) {

    List<String> changes = new ArrayList<>();

    for (Lesson current : fresh.values()) {
      Lesson previous = oldLessons.get(current.id);

      if (previous == null) {
        changes.add("Добавлено занятие: " + current.subject + " (" +
                    current.start.format(DATE_FORMAT) + ").");
      } else if (!Objects.equals(oldSnapshot.get(current.id),
                                 freshSnapshot.get(current.id))) {
        changes.add("Изменено занятие: " + current.subject + " (" +
                    current.start.format(DATE_FORMAT) + ").");
      }
    }

    for (Lesson previous : oldLessons.values()) {
      if (!fresh.containsKey(previous.id)) {
        changes.add("Удалено занятие: " + previous.subject + " (" +
                    previous.start.format(DATE_FORMAT) + ").");
      }
    }

    return changes;
  }

  private void rememberChange(String message) {
    synchronized (changeHistory) {
      changeHistory.add(0, message);
      while (changeHistory.size() > maxHistory) {
        changeHistory.remove(changeHistory.size() - 1);
      }
    }
  }

  private void broadcast(Component message) {
    for (CommandSender sender : Bukkit.getOnlinePlayers()) {
      if (sender.hasPermission("universityschedule.notify")) {
        sender.sendMessage(message);
      }
    }
    getLogger().info(
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText()
            .serialize(message));
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label,
                           String[] args) {

    if (command.getName().equalsIgnoreCase("day")) {
      if (args.length != 1) {
        sender.sendMessage(Component.text(
            "Использование: /day <now|yyyy-MM-dd>", NamedTextColor.RED));
        return true;
      }

      LocalDate date;
      if (args[0].equalsIgnoreCase("now")) {
        date = LocalDate.now(zone);
      } else {
        try {
          date = LocalDate.parse(args[0]);
        } catch (DateTimeParseException e) {
          sender.sendMessage(Component.text(
              "Дата должна быть в формате yyyy-MM-dd.", NamedTextColor.RED));
          return true;
        }
      }

      showDay(sender, date);
      return true;
    }

    if (command.getName().equalsIgnoreCase("week")) {
      if (args.length != 1) {
        sender.sendMessage(
            Component.text("Использование: /week <this|next|prev|number>",
                           NamedTextColor.RED));
        return true;
      }

      LocalDate current = LocalDate.now(zone);
      int currentWeek = weekNumber(current);
      int requested;

      switch (args[0].toLowerCase(Locale.ROOT)) {
      case "this" -> requested = currentWeek;
      case "next" -> requested = currentWeek + 1;
      case "prev" -> requested = currentWeek - 1;
      default -> {
        try {
          requested = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
          sender.sendMessage(Component.text(
              "Укажи this, next, prev или номер недели.", NamedTextColor.RED));
          return true;
        }
      }
      }

      showWeek(sender, requested);
      return true;
    }

    if (command.getName().equalsIgnoreCase("changes")) {
      synchronized (changeHistory) {
        if (changeHistory.isEmpty()) {
          sender.sendMessage(Component.text("Изменений пока не обнаружено.",
                                            NamedTextColor.GRAY));
        } else {
          sender.sendMessage(Component.text("Последние изменения расписания:",
                                            NamedTextColor.GOLD));
          for (String change : changeHistory) {
            sender.sendMessage(
                Component.text("• " + change, NamedTextColor.YELLOW));
          }
        }
      }
      return true;
    }

    return false;
  }

  private void showDay(CommandSender sender, LocalDate date) {
    if (!loaded) {
      sender.sendMessage(Component.text(
          "Расписание ещё загружается. Попробуй через несколько секунд.",
          NamedTextColor.YELLOW));
      return;
    }

    sender.sendMessage(Component.text(
        "Расписание на " + date.format(DATE_FORMAT), NamedTextColor.GOLD));

    List<Lesson> dayLessons =
        lessons.values()
            .stream()
            .filter(l -> l.start.toLocalDate().equals(date))
            .sorted(Comparator.comparing(l -> l.start))
            .limit(maxLessonsPerDay)
            .toList();

    if (dayLessons.isEmpty()) {
      sender.sendMessage(Component.text("Занятий нет.", NamedTextColor.GRAY));
      return;
    }

    for (Lesson lesson : dayLessons) {
      showLesson(sender, lesson);
    }
  }

  private void showWeek(CommandSender sender, int number) {
    int totalWeeks = totalWeeks();

    if (number < 1 || number > totalWeeks) {
      sender.sendMessage(Component.text(
          "Номер недели вне семестра. Допустимо: 1–" + totalWeeks,
          NamedTextColor.RED));
      return;
    }

    LocalDate start = semesterStart.plusWeeks(number - 1L);
    LocalDate end = start.plusDays(6);
    LocalDate actualEnd = end.isAfter(semesterEnd) ? semesterEnd : end;

    sender.sendMessage(Component.text("Неделя " + number + " (" +
                                          start.format(DATE_FORMAT) + " – " +
                                          actualEnd.format(DATE_FORMAT) + ")",
                                      NamedTextColor.GOLD));

    List<Lesson> weekLessons =
        lessons.values()
            .stream()
            .filter(l -> {
              LocalDate d = l.start.toLocalDate();
              return !d.isBefore(start) && !d.isAfter(actualEnd);
            })
            .sorted(Comparator.comparing((Lesson l) -> l.start))
            .toList();

    if (weekLessons.isEmpty()) {
      sender.sendMessage(
          Component.text("Занятий в этой неделе нет.", NamedTextColor.GRAY));
      return;
    }

    LocalDate lastDate = null;
    for (Lesson lesson : weekLessons) {
      LocalDate date = lesson.start.toLocalDate();
      if (!date.equals(lastDate)) {
        sender.sendMessage(
            Component.text("\n" +
                               date.getDayOfWeek().getDisplayName(
                                   java.time.format.TextStyle.FULL,
                                   Locale.forLanguageTag("ru")) +
                               ", " + date.format(DATE_FORMAT),
                           NamedTextColor.AQUA));
        lastDate = date;
      }
      showLesson(sender, lesson);
    }
  }

  private void showLesson(CommandSender sender, Lesson lesson) {
    TextColor color =
        useApiColors ? parseColor(lesson.color, defaultColor) : defaultColor;

    Component time = Component.text(
        lesson.start.format(TIME_FORMAT) + "–" + lesson.end.format(TIME_FORMAT),
        timeBackground ? NamedTextColor.WHITE : color);

    Component line = time.append(Component.space())
                         .append(Component.text(lesson.subject, color));

    if (lesson.replacement) {
      Component tag =
          Component.text(" [ЗАМЕНА]", NamedTextColor.RED)
              .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);

      line = line.append(tag);
    }

    sender.sendMessage(line);

    if (!lesson.teacher.isBlank()) {
      sender.sendMessage(Component.text("  Преподаватель: " + lesson.teacher,
                                        NamedTextColor.GRAY));
    }

    sender.sendMessage(
        Component.text("  Аудитория: " + lesson.room, NamedTextColor.GRAY));
  }

  private int weekNumber(LocalDate date) {
    if (date.isBefore(semesterStart) || date.isAfter(semesterEnd)) {
      return -1;
    }
    return (int)(ChronoUnit.WEEKS.between(
                     semesterStart,
                     date.with(
                         java.time.temporal.TemporalAdjusters.previousOrSame(
                             java.time.DayOfWeek.MONDAY))) -
                 ChronoUnit.WEEKS.between(
                     semesterStart,
                     semesterStart.with(
                         java.time.temporal.TemporalAdjusters.previousOrSame(
                             java.time.DayOfWeek.MONDAY)))) +
        1;
  }

  private int totalWeeks() {
    LocalDate firstMonday =
        semesterStart.with(java.time.temporal.TemporalAdjusters.previousOrSame(
            java.time.DayOfWeek.MONDAY));
    LocalDate lastMonday =
        semesterEnd.with(java.time.temporal.TemporalAdjusters.previousOrSame(
            java.time.DayOfWeek.MONDAY));
    return (int)ChronoUnit.WEEKS.between(firstMonday, lastMonday) + 1;
  }

  private static TextColor parseColor(String raw, TextColor fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return TextColor.fromHexString(raw.trim());
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }

  private static String getString(JsonObject o, String key, String fallback) {
    JsonElement value = o.get(key);
    return value == null || value.isJsonNull() ? fallback : value.getAsString();
  }

  private static int getInt(JsonObject o, String key, int fallback) {
    JsonElement value = o.get(key);
    if (value == null || value.isJsonNull()) {
      return fallback;
    }
    try {
      return value.getAsInt();
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private static boolean getBoolean(JsonObject o, String key,
                                    boolean fallback) {
    JsonElement value = o.get(key);
    if (value == null || value.isJsonNull()) {
      return fallback;
    }
    try {
      return value.getAsBoolean();
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private record
      Lesson(int id, LocalDateTime start, LocalDateTime end, String subject,
             String teacher, String room, String color, boolean replacement) {

    String signature() {
      return start + "|" + end + "|" + subject + "|" + teacher + "|" + room +
          "|" + color + "|" + replacement;
    }
  }
}
