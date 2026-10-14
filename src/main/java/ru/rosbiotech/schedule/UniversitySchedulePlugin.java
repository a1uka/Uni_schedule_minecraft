
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
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
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
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class UniversitySchedulePlugin
    extends JavaPlugin implements Listener {

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command,
                                    String alias, String[] args) {
    if (args.length != 1) {
      return Collections.emptyList();
    }

    String input = args[0].toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();

    switch (command.getName().toLowerCase(Locale.ROOT)) {
    case "day" -> {
      suggestions.add("today");
      suggestions.add("tomorrow");

      LocalDate today = LocalDate.now(zone);

      for (int i = 0; i <= 7; i++) {
        LocalDate date = today.plusDays(i);

        if (!date.isBefore(semesterStart) && !date.isAfter(semesterEnd)) {
          suggestions.add(date.toString());
        }
      }
    }

    case "week" -> {
      suggestions.add("this");

      int currentWeek = weekNumber(LocalDate.now(zone));
      int total = totalWeeks();

      if (currentWeek >= 1 && currentWeek <= total) {
        suggestions.add(String.valueOf(currentWeek));
      }

      suggestions.add("next");
      suggestions.add("prev");

      if (currentWeek >= 1 && currentWeek <= total) {
        int firstWeek = Math.max(1, currentWeek - 3);
        int lastWeek = Math.min(total, currentWeek + 3);

        for (int i = firstWeek; i <= lastWeek; i++) {
          if (i != currentWeek) {
            suggestions.add(String.valueOf(i));
          }
        }
      }
    }

    case "changes" -> {
      return Collections.emptyList();
    }
    }

    return suggestions.stream().filter(s -> s.startsWith(input)).toList();
  }

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
    Bukkit.getPluginManager().registerEvents(this, this);

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
            "Использование: /uni:day <today|tomorrow|yyyy-MM-dd>",
            NamedTextColor.RED));
        return true;
      }

      LocalDate date;
      String argument = args[0].toLowerCase(Locale.ROOT);

      switch (argument) {
      case "today" -> date = LocalDate.now(zone);
      case "tomorrow" -> date = LocalDate.now(zone).plusDays(1);
      default -> {
        try {
          date = LocalDate.parse(args[0]);
        } catch (DateTimeParseException e) {
          sender.sendMessage(Component.text(
              "Использование: /uni:day <today|tomorrow|yyyy-MM-dd>",
              NamedTextColor.RED));
          return true;
        }
      }
      }

      showDay(sender, date);
      return true;
    }

    if (command.getName().equalsIgnoreCase("week")) {
      if (args.length != 1) {
        sender.sendMessage(
            Component.text("Использование: /uni:week <this|next|prev|number>",
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

  private void showWeek(CommandSender sender, int weekNumber) {
    int total = totalWeeks();

    if (weekNumber < 1 || weekNumber > total) {
      sender.sendMessage(Component.text(
          "Неделя должна быть от 1 до " + total + ".", NamedTextColor.RED));
      return;
    }

    if (!(sender instanceof Player player)) {
      sender.sendMessage("Открыть расписание в сундуке может только игрок.");
      return;
    }

    openWeekMenu(player, weekNumber);
  }

  private void showLesson(CommandSender sender, Lesson lesson) {
    TextColor color =
        useApiColors ? parseColor(lesson.color, defaultColor) : defaultColor;

    LocalDateTime now = LocalDateTime.now(zone);

    Component marker;
    if (!now.isBefore(lesson.start) && now.isBefore(lesson.end)) {
      marker =
          Component.text("▶ СЕЙЧАС", NamedTextColor.GREEN)
              .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);
    } else if (!now.isBefore(lesson.end)) {
      marker = Component.text("✓", NamedTextColor.GRAY);
    } else {
      marker = Component.text("○", NamedTextColor.AQUA);
    }

    Component time = Component.text(
        lesson.start.format(TIME_FORMAT) + "–" + lesson.end.format(TIME_FORMAT),
        timeBackground ? NamedTextColor.WHITE : color);

    Component line = marker.append(Component.space())
                         .append(time)
                         .append(Component.space())
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

  private LocalDate semesterFirstMonday() {
    return semesterStart.with(
        java.time.temporal.TemporalAdjusters.previousOrSame(
            java.time.DayOfWeek.MONDAY));
  }

  private LocalDate semesterLastMonday() {
    return semesterEnd.with(java.time.temporal.TemporalAdjusters.previousOrSame(
        java.time.DayOfWeek.MONDAY));
  }

  private int weekNumber(LocalDate date) {
    if (date.isBefore(semesterStart) || date.isAfter(semesterEnd)) {
      return -1;
    }

    LocalDate dateMonday =
        date.with(java.time.temporal.TemporalAdjusters.previousOrSame(
            java.time.DayOfWeek.MONDAY));

    return (int)ChronoUnit.WEEKS.between(semesterFirstMonday(), dateMonday) + 1;
  }

  private int totalWeeks() {
    return (int)ChronoUnit.WEEKS.between(semesterFirstMonday(),
                                         semesterLastMonday()) +
        1;
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
  private static final int[] DAY_SLOTS = {10, 11, 12, 13, 14, 15, 16};
  private static final int PREVIOUS_SLOT = 45;
  private static final int CLOSE_SLOT = 49;
  private static final int NEXT_SLOT = 53;

  private static final class WeekHolder implements InventoryHolder {
    private final int week;
    private Inventory inventory;

    private WeekHolder(int week) { this.week = week; }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  private static final class DayHolder implements InventoryHolder {
    private final int week;
    private final LocalDate date;
    private Inventory inventory;

    private DayHolder(int week, LocalDate date) {
      this.week = week;
      this.date = date;
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  private ItemStack menuItem(Material material, Component name,
                             List<Component> lore) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();

    meta.displayName(name);
    meta.lore(lore);

    item.setItemMeta(meta);
    return item;
  }

  private ItemStack menuItem(Material material, Component name) {
    return menuItem(material, name, List.of());
  }

  private String capitalize(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }

    return text.substring(0, 1).toUpperCase(Locale.forLanguageTag("ru")) +
        text.substring(1);
  }

  private List<Lesson> lessonsForDate(LocalDate date) {
    return lessons.values()
        .stream()
        .filter(lesson -> lesson.start().toLocalDate().equals(date))
        .sorted(java.util.Comparator.comparing(Lesson::start))
        .limit(getConfig().getInt("display.max-lessons-per-day", 12))
        .toList();
  }

  private void openWeekMenu(Player player, int week) {
    WeekHolder holder = new WeekHolder(week);

    Inventory inventory = Bukkit.createInventory(
        holder, 54,
        Component.text("Uni | Неделя " + week, NamedTextColor.DARK_AQUA));

    holder.inventory = inventory;

    LocalDate monday = semesterFirstMonday().plusWeeks(week - 1L);
    LocalDate semesterStart =
        LocalDate.parse(getConfig().getString("semester.start-date"));
    LocalDate semesterEnd =
        LocalDate.parse(getConfig().getString("semester.end-date"));

    inventory.setItem(
        4, menuItem(Material.BOOK,
                    Component.text("Неделя " + week, NamedTextColor.GOLD),
                    List.of(Component.text(monday + " — " + monday.plusDays(6),
                                           NamedTextColor.GRAY),
                            Component.text("Выбери день, чтобы посмотреть пары",
                                           NamedTextColor.YELLOW))));

    for (int i = 0; i < 7; i++) {
      LocalDate date = monday.plusDays(i);
      boolean inSemester =
          !date.isBefore(semesterStart) && !date.isAfter(semesterEnd);

      if (!inSemester) {
        inventory.setItem(
            DAY_SLOTS[i],
            menuItem(
                Material.GRAY_STAINED_GLASS_PANE,
                Component.text(
                    capitalize(date.getDayOfWeek().getDisplayName(
                        TextStyle.FULL, Locale.forLanguageTag("ru"))),
                    NamedTextColor.GRAY),
                List.of(
                    Component.text(date.toString(), NamedTextColor.DARK_GRAY),
                    Component.text("Вне семестра", NamedTextColor.RED))));
        continue;
      }

      int count = lessonsForDate(date).size();
      boolean today = date.equals(LocalDate.now(java.time.ZoneId.of(
          getConfig().getString("display.timezone", "Europe/Moscow"))));

      Material material = today ? Material.CLOCK : Material.PAPER;
      NamedTextColor color = today ? NamedTextColor.GREEN : NamedTextColor.AQUA;

      inventory.setItem(
          DAY_SLOTS[i],
          menuItem(
              material,
              Component.text(capitalize(date.getDayOfWeek().getDisplayName(
                                 TextStyle.FULL, Locale.forLanguageTag("ru"))),
                             color),
              List.of(Component.text(date.toString(), NamedTextColor.GRAY),
                      Component.text("Пар: " + count, NamedTextColor.YELLOW),
                      Component.text("Нажми, чтобы открыть",
                                     NamedTextColor.GREEN))));
    }

    if (week > 1) {
      inventory.setItem(
          PREVIOUS_SLOT,
          menuItem(Material.ARROW, Component.text("← Предыдущая неделя",
                                                  NamedTextColor.YELLOW)));
    }

    inventory.setItem(CLOSE_SLOT,
                      menuItem(Material.BARRIER,
                               Component.text("Закрыть", NamedTextColor.RED)));

    if (week < totalWeeks()) {
      inventory.setItem(
          NEXT_SLOT,
          menuItem(Material.ARROW, Component.text("Следующая неделя →",
                                                  NamedTextColor.YELLOW)));
    }

    player.openInventory(inventory);
  }

  private void openDayMenu(Player player, int week, LocalDate date) {
    DayHolder holder = new DayHolder(week, date);

    Inventory inventory = Bukkit.createInventory(
        holder, 54, Component.text("Uni | " + date, NamedTextColor.DARK_AQUA));

    holder.inventory = inventory;

    inventory.setItem(
        4, menuItem(Material.CLOCK,
                    Component.text(
                        capitalize(date.getDayOfWeek().getDisplayName(
                            TextStyle.FULL, Locale.forLanguageTag("ru"))) +
                            ", " + date,
                        NamedTextColor.GOLD),
                    List.of(Component.text("Найдено пар: " +
                                               lessonsForDate(date).size(),
                                           NamedTextColor.YELLOW))));

    List<Lesson> dayLessons = lessonsForDate(date);
    int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
                   28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    for (int i = 0; i < dayLessons.size() && i < slots.length; i++) {
      Lesson lesson = dayLessons.get(i);

      String time =
          lesson.start().toLocalTime() + "–" + lesson.end().toLocalTime();

      List<Component> lore = new ArrayList<>();
      lore.add(Component.text(time, NamedTextColor.YELLOW));

      if (lesson.teacher() != null && !lesson.teacher().isBlank()) {
        lore.add(Component.text("Преподаватель: " + lesson.teacher(),
                                NamedTextColor.GRAY));
      }

      if (lesson.room() != null && !lesson.room().isBlank()) {
        lore.add(
            Component.text("Аудитория: " + lesson.room(), NamedTextColor.GRAY));
      }

      inventory.setItem(slots[i], menuItem(Material.BOOK,
                                           Component.text(lesson.subject(),
                                                          NamedTextColor.AQUA),
                                           lore));
    }

    inventory.setItem(
        PREVIOUS_SLOT,
        menuItem(Material.ARROW,
                 Component.text("← К неделе " + week, NamedTextColor.YELLOW)));

    inventory.setItem(CLOSE_SLOT,
                      menuItem(Material.BARRIER,
                               Component.text("Закрыть", NamedTextColor.RED)));

    player.openInventory(inventory);
  }

  @EventHandler
  public void onScheduleMenuClick(InventoryClickEvent event) {
    Inventory top = event.getView().getTopInventory();
    InventoryHolder holder = top.getHolder();

    if (!(holder instanceof WeekHolder) && !(holder instanceof DayHolder)) {
      return;
    }

    event.setCancelled(true);

    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }

    if (event.getClickedInventory() != top) {
      return;
    }

    int slot = event.getRawSlot();

    if (holder instanceof WeekHolder weekHolder) {
      int week = weekHolder.week;

      for (int i = 0; i < DAY_SLOTS.length; i++) {
        if (slot == DAY_SLOTS[i]) {
          LocalDate date =
              semesterFirstMonday().plusWeeks(week - 1L).plusDays(i);

          LocalDate start =
              LocalDate.parse(getConfig().getString("semester.start-date"));
          LocalDate end =
              LocalDate.parse(getConfig().getString("semester.end-date"));

          if (!date.isBefore(start) && !date.isAfter(end)) {
            openDayMenu(player, week, date);
          }
          return;
        }
      }

      if (slot == PREVIOUS_SLOT && week > 1) {
        openWeekMenu(player, week - 1);
      } else if (slot == NEXT_SLOT && week < totalWeeks()) {
        openWeekMenu(player, week + 1);
      } else if (slot == CLOSE_SLOT) {
        player.closeInventory();
      }

      return;
    }

    if (holder instanceof DayHolder dayHolder) {
      if (slot == PREVIOUS_SLOT) {
        openWeekMenu(player, dayHolder.week);
      } else if (slot == CLOSE_SLOT) {
        player.closeInventory();
      }
    }
  }

  @EventHandler
  public void onScheduleMenuDrag(InventoryDragEvent event) {
    InventoryHolder holder = event.getView().getTopInventory().getHolder();

    if (holder instanceof WeekHolder || holder instanceof DayHolder) {
      event.setCancelled(true);
    }
  }
}
