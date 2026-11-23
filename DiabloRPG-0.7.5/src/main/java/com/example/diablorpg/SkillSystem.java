package com.example.diablorpg;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 직업 / 스킬 트리 / 저주 / 오라 / 장비 스킬 옵션 시스템 (0.7.x). [전부 미검증: 작성자가 빌드/실행해 보지 못했다]
 *
 * 설계 요약
 *  - 직업·스킬·오라·저주·발동 옵션은 전부 config.yml(classes / skills / skill-procs)로 정의한다.
 *    kind: melee(근접 공격) / aoe(주변 범위) / fear(공포) / whirlwind(회전) / leap(도약) / leap_attack(대상에게 도약)
 *          / buff(강화) / passive(패시브) / curse(저주) / aura(오라) / display(효과 없이 표시만)
 *  - 수치는 {base, per-level, per-level2, max} / {points: {레벨: 값}} / {values: [..]} / 숫자 하나.
 *  - synergy: 다른 스킬의 레벨 x per-level 만큼 특정 수치(stat)에 더한다(mode: pct 이면 곱셈 %).
 *  - 스킬 사용 조건: 무기가 핫바 1번 칸이고 손에 들고 있을 때만 (config skill-system.require-slot-one).
 *  - 재사용 대기시간은 없다. 공격 스킬(speed: attack)은 공격 속도, 함성 스킬(speed: cast)은 시전 속도가 간격을 정한다.
 *    lock 이 같은 스킬은 이전 동작이 끝나기 전에 다시 쓸 수 없다(도약류: 착지 전, 소용돌이: 회전 중).
 *  - 패시브/버프/오라의 능력치는 "합성 PDC"로 만들어 기존 전투/방어/능력치 계산에 합산한다.
 *  - 스킬 피해는 target.damage(양, 플레이어)로 주기 때문에 기존 피해 파이프라인을 그대로 탄다.
 *    "마법 피해"(비전)와 오라 틱 피해, 전장의 함성 고정 피해는 pure 플래그로 파이프라인을 건너뛴다.
 */
final class SkillSystem implements Listener {

    // ===================================================================
    //  데이터 클래스
    // ===================================================================

    static final class ClassDef {
        String id;
        String name;
        Material icon = Material.IRON_SWORD;
        String desc = "";
        List<String> tabs = new ArrayList<>();
    }

    static final class Syn {
        String skill;
        String stat;
        double per;
        boolean pct;
    }

    static final class SkillDef {
        String id;
        String name;
        String classId = "";
        String kind = "passive";
        String desc = "";
        String speed = "none"; // attack / cast / none
        String lock = "";      // 같은 lock 끼리는 동작이 끝나야 다시 쓸 수 있다
        Material icon = Material.PAPER;
        int tab;
        int tier;
        int row;
        int col;
        int maxLevel = 20;
        int reqLevel = 0;
        List<String> prereq = new ArrayList<>();
        List<Syn> syn = new ArrayList<>();
        ConfigurationSection sec;
    }

    static final class ProcDef {
        String id;
        String optionName;
        String trigger = "hit";
        String cast;
        int level = 1;
    }

    static final class PData {
        String cls;                 // 직업 id (없으면 null)
        int bonusPoints;            // 관리자가 준 추가 포인트
        int maxLevel;               // 지금까지 도달한 최대 바닐라 레벨(포인트/요구 레벨 계산용)
        boolean hotkeys = true;     // 숫자키로 스킬 선택
        String left;                // 좌클릭 스킬 (null = 기본 공격)
        String right;               // 우클릭 스킬
        String activeAura;          // 클래스 오라(토글)
        final Map<String, Integer> levels = new LinkedHashMap<>();
        final String[] hot = new String[9]; // 인덱스 1~8 = 핫바 2~9번 칸
    }

    static final class Buff {
        final String id;
        final int level;
        final long until;

        Buff(String id, int level, long until) {
            this.id = id;
            this.level = level;
            this.until = until;
        }
    }

    static final class Curse {
        final double amount;
        final long until;

        Curse(double amount, long until) {
            this.amount = amount;
            this.until = until;
        }
    }

    /** 광분: 적중마다 쌓이는 속도 보너스. */
    static final class Frenzy {
        String skillId;
        int level;
        int stacks;
        long until;
    }

    static final class Gear {
        int all;
        int castSpeed;
        final Map<String, Integer> cls = new HashMap<>();
        final Map<String, Integer> lv = new HashMap<>();
        final Map<String, Integer> aura = new HashMap<>();
        final Map<String, Integer> proc = new HashMap<>();
    }

    static final class Cached {
        final int tick;
        final PersistentDataContainer pdc;
        final Map<String, Double> totals;

        Cached(int tick, PersistentDataContainer pdc, Map<String, Double> totals) {
            this.tick = tick;
            this.pdc = pdc;
            this.totals = totals;
        }
    }

    static final class SkillHolder implements InventoryHolder {
        final String type; // "tree" / "class"
        int tab;
        Inventory inv;
        final Map<Integer, String> slotSkill = new HashMap<>();

        SkillHolder(String type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private enum Result { FAIL, OK, WAIT }

    // ===================================================================
    //  필드
    // ===================================================================

    private static final Material[] TAB_ICONS = {Material.IRON_SWORD, Material.BELL, Material.SHIELD};
    private static final int[] TIER_LEVELS = {1, 6, 12, 18, 24, 30};
    private static final int GUI_SIZE = 54;
    private static final int STAR_SLOT = 35;
    private static final int BOOK_SLOT = 44;

    private final DiabloRPG plugin;
    private final Random random = new Random();
    private final NamespacedKey dataKey;

    private final Map<String, ClassDef> classes = new LinkedHashMap<>();
    private final Map<String, SkillDef> skills = new LinkedHashMap<>();
    private final Map<String, ProcDef> procs = new LinkedHashMap<>();
    private final Map<String, String> optionKeys = new HashMap<>();          // 옵션 이름 -> PDC 키 이름
    private final Map<String, NamespacedKey> keyCache = new HashMap<>();

    private final Map<UUID, PData> data = new HashMap<>();
    private final Map<UUID, Map<String, Buff>> buffs = new HashMap<>();
    private final Map<UUID, Map<String, Buff>> auraBuffs = new HashMap<>();
    private final Map<UUID, Map<String, Curse>> curses = new HashMap<>();
    private final Map<UUID, Frenzy> frenzy = new HashMap<>();
    private final Map<UUID, String> locks = new HashMap<>();
    private final Map<UUID, Long> nextSwing = new HashMap<>();
    private final Map<UUID, Long> castingUntil = new HashMap<>();
    private final Map<UUID, Long> leapGuard = new HashMap<>();
    private final Map<UUID, Long> feared = new HashMap<>();
    private final Map<UUID, Integer> lastLeftCast = new HashMap<>();
    private final Map<UUID, Cached> bonusCache = new HashMap<>();
    private final Map<UUID, Object[]> gearCache = new HashMap<>(); // {Integer tick, Gear}

    private boolean casting = false;    // 스킬이 주는 피해 중 (재귀/발동 방지)
    private boolean pure = false;       // 기존 피해 파이프라인을 건너뛰는 피해
    private boolean reflecting = false; // 강철 처녀 반사 중

    SkillSystem(DiabloRPG plugin) {
        this.plugin = plugin;
        this.dataKey = new NamespacedKey(plugin, "skill_data");
        reload();
    }

    boolean isPureHit() {
        return pure;
    }

    boolean isCasting() {
        return casting;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::fastTick, 20L, 10L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::slowTick, 40L, 20L);
    }

    // ===================================================================
    //  config 읽기
    // ===================================================================

    private static final java.util.regex.Pattern ID_PATTERN = java.util.regex.Pattern.compile("[a-z0-9_]+");

    void reload() {
        classes.clear();
        skills.clear();
        procs.clear();
        optionKeys.clear();
        keyCache.clear();

        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("classes");
        if (cs != null) {
            for (String id : cs.getKeys(false)) {
                ConfigurationSection c = cs.getConfigurationSection(id);
                if (c == null || !ID_PATTERN.matcher(id).matches()) continue;
                ClassDef def = new ClassDef();
                def.id = id;
                def.name = c.getString("name", id);
                def.icon = mat(c.getString("icon"), Material.IRON_SWORD);
                def.desc = c.getString("description", "");
                def.tabs.addAll(c.getStringList("tabs"));
                if (def.tabs.isEmpty()) def.tabs.add("스킬");
                classes.put(id, def);
            }
        }

        ConfigurationSection ss = plugin.getConfig().getConfigurationSection("skills");
        if (ss != null) {
            for (String id : ss.getKeys(false)) {
                ConfigurationSection c = ss.getConfigurationSection(id);
                if (c == null) continue;
                if (!ID_PATTERN.matcher(id).matches()) {
                    plugin.getLogger().warning("[스킬] 스킬 id는 영문 소문자/숫자/_ 만 가능: " + id);
                    continue;
                }
                SkillDef d = new SkillDef();
                d.id = id;
                d.name = c.getString("name", id);
                d.classId = c.getString("class", "");
                d.kind = c.getString("kind", "passive").toLowerCase(Locale.ROOT);
                d.desc = c.getString("description", "");
                d.speed = c.getString("speed", "none").toLowerCase(Locale.ROOT);
                d.lock = c.getString("lock", "");
                d.icon = mat(c.getString("icon"), Material.PAPER);
                d.tab = c.getInt("tab", 0);
                d.tier = c.getInt("tier", 0);
                if (d.tier >= 1 && d.tier <= TIER_LEVELS.length) {
                    d.reqLevel = TIER_LEVELS[d.tier - 1];
                    d.row = d.tier - 1;
                } else {
                    d.reqLevel = c.getInt("req-level", 0);
                    d.row = Math.max(0, Math.min(5, c.getInt("row", 0)));
                }
                d.col = Math.max(0, Math.min(7, c.getInt("col", 0)));
                d.maxLevel = Math.max(1, c.getInt("max-level", 20));
                d.prereq.addAll(c.getStringList("prereq"));
                for (Map<?, ?> m : c.getMapList("synergy")) {
                    Syn sy = new Syn();
                    sy.skill = String.valueOf(m.get("skill"));
                    sy.stat = String.valueOf(m.get("stat"));
                    Object per = m.get("per-level");
                    sy.per = per instanceof Number n ? n.doubleValue() : 0.0;
                    sy.pct = "pct".equals(String.valueOf(m.get("mode")));
                    d.syn.add(sy);
                }
                d.sec = c;
                skills.put(id, d);
            }
        }

        ConfigurationSection ps = plugin.getConfig().getConfigurationSection("skill-procs");
        if (ps != null) {
            for (String id : ps.getKeys(false)) {
                ConfigurationSection c = ps.getConfigurationSection(id);
                if (c == null || !ID_PATTERN.matcher(id).matches()) continue;
                ProcDef p = new ProcDef();
                p.id = id;
                p.optionName = c.getString("option-name", id);
                p.trigger = c.getString("trigger", "hit").toLowerCase(Locale.ROOT);
                p.cast = c.getString("cast", "");
                p.level = Math.max(1, c.getInt("level", 1));
                procs.put(id, p);
            }
        }

        // 장비 옵션 이름 -> PDC 키
        optionKeys.put("모든 스킬", "sk_all");
        optionKeys.put("시전 속도", "cast_speed");
        for (ClassDef c : classes.values()) optionKeys.put(c.name + " 스킬", "sk_class_" + c.id);
        for (SkillDef s : skills.values()) {
            optionKeys.put(s.name + " 레벨", "sk_lv_" + s.id);
            if (s.kind.equals("aura")) optionKeys.put(s.name, "au_" + s.id);
        }
        for (ProcDef p : procs.values()) optionKeys.put(p.optionName, "pr_" + p.id);
    }

    private static Material mat(String name, Material def) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name);
        return m == null ? def : m;
    }

    /**
     * 레벨에 맞는 수치.
     *  - 숫자 하나: 그대로
     *  - {base, per-level, per-level2, max}: base + per-level*(L-1) + per-level2*(L-1)^2
     *  - {points: {1: 7, 5: 25}}: 주어진 레벨 사이를 직선으로 보간
     *  - {values: [..]}: 레벨 순서 목록 (넘어가면 마지막 값)
     */
    static double scaled(ConfigurationSection parent, String key, int level, double def) {
        if (parent == null) return def;
        if (parent.isConfigurationSection(key)) {
            ConfigurationSection c = parent.getConfigurationSection(key);
            double v;
            if (c.isList("values")) {
                List<Double> vals = c.getDoubleList("values");
                v = vals.isEmpty() ? def : vals.get(Math.max(0, Math.min(vals.size() - 1, level - 1)));
            } else if (c.isConfigurationSection("points")) {
                v = interpolate(c.getConfigurationSection("points"), level, def);
            } else {
                int n = Math.max(0, level - 1);
                v = c.getDouble("base", def) + c.getDouble("per-level", 0.0) * n + c.getDouble("per-level2", 0.0) * n * n;
            }
            if (c.isSet("max")) v = Math.min(v, c.getDouble("max"));
            return v;
        }
        return parent.getDouble(key, def);
    }

    private static double interpolate(ConfigurationSection pts, int level, double def) {
        TreeMap<Integer, Double> m = new TreeMap<>();
        for (String k : pts.getKeys(false)) {
            try {
                m.put(Integer.parseInt(k.trim()), pts.getDouble(k));
            } catch (NumberFormatException ignored) {
                // 잘못된 키는 무시
            }
        }
        if (m.isEmpty()) return def;
        Map.Entry<Integer, Double> lo = m.floorEntry(level);
        Map.Entry<Integer, Double> hi = m.ceilingEntry(level);
        if (lo == null) return hi.getValue();
        if (hi == null) return lo.getValue();
        if (lo.getKey().equals(hi.getKey())) return lo.getValue();
        double t = (level - lo.getKey()) / (double) (hi.getKey() - lo.getKey());
        return lo.getValue() + (hi.getValue() - lo.getValue()) * t;
    }

    /** 스킬 수치 + 시너지(다른 스킬의 레벨 x 레벨당 보너스). */
    private double val(Player p, SkillDef s, String key, int lv, double def) {
        double v = scaled(s.sec, key, lv, def);
        if (s.syn.isEmpty()) return v;
        PData d = data(p);
        double pctSum = 0.0;
        for (Syn sy : s.syn) {
            if (!sy.stat.equals(key)) continue;
            SkillDef src = skills.get(sy.skill);
            if (src == null) continue;
            int sl = level(p, d, src);
            if (sl <= 0) continue;
            if (sy.pct) pctSum += sy.per * sl;
            else v += sy.per * sl;
        }
        if (pctSum != 0.0) v *= 1.0 + pctSum / 100.0;
        return v;
    }

    private double lengthScale() {
        return plugin.getConfig().getDouble("skill-system.length-scale", 0.5);
    }

    /** 범위(칸). radius-yd 가 있으면 원작 야드 값에 길이 변환 비율을 곱한다. */
    private double radius(Player p, SkillDef s, int lv, double def) {
        if (s.sec.contains("radius-yd")) return val(p, s, "radius-yd", lv, def) * lengthScale();
        return val(p, s, "radius", lv, def);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("skill-system.enabled", true);
    }

    private NamespacedKey key(String name) {
        return keyCache.computeIfAbsent(name, n -> new NamespacedKey(plugin, n));
    }

    /** 장비 옵션(모든 스킬/직업 스킬/스킬 레벨/오라/발동/시전 속도)을 아이템 PDC에 쓴다. 해당되면 true. */
    boolean applyDynamicOption(PersistentDataContainer pdc, String optionName, int value) {
        String k = optionKeys.get(optionName);
        if (k == null) return false;
        pdc.set(key(k), PersistentDataType.DOUBLE, (double) value);
        return true;
    }

    // ===================================================================
    //  플레이어 데이터 저장/불러오기
    // ===================================================================

    private PData data(Player p) {
        PData d = data.get(p.getUniqueId());
        if (d == null) {
            d = parse(p.getPersistentDataContainer().get(dataKey, PersistentDataType.STRING));
            data.put(p.getUniqueId(), d);
        }
        return d;
    }

    private void save(Player p) {
        PData d = data.get(p.getUniqueId());
        if (d == null) return;
        p.getPersistentDataContainer().set(dataKey, PersistentDataType.STRING, serialize(d));
        bonusCache.remove(p.getUniqueId());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String serialize(PData d) {
        StringBuilder lv = new StringBuilder();
        for (Map.Entry<String, Integer> e : d.levels.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (lv.length() > 0) lv.append(',');
            lv.append(e.getKey()).append(':').append(e.getValue());
        }
        StringBuilder hot = new StringBuilder();
        for (int i = 1; i < d.hot.length; i++) {
            if (d.hot[i] == null) continue;
            if (hot.length() > 0) hot.append(',');
            hot.append(i).append(':').append(d.hot[i]);
        }
        return "c=" + nz(d.cls) + "|b=" + d.bonusPoints + "|m=" + d.maxLevel + "|hk=" + (d.hotkeys ? 1 : 0)
                + "|l=" + nz(d.left) + "|r=" + nz(d.right) + "|a=" + nz(d.activeAura)
                + "|v=" + lv + "|h=" + hot;
    }

    private static PData parse(String s) {
        PData d = new PData();
        if (s == null || s.isEmpty()) return d;
        for (String part : s.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq);
            String v = part.substring(eq + 1);
            try {
                switch (k) {
                    case "c" -> d.cls = v.isEmpty() ? null : v;
                    case "b" -> d.bonusPoints = Integer.parseInt(v);
                    case "m" -> d.maxLevel = Integer.parseInt(v);
                    case "hk" -> d.hotkeys = !v.equals("0");
                    case "l" -> d.left = v.isEmpty() ? null : v;
                    case "r" -> d.right = v.isEmpty() ? null : v;
                    case "a" -> d.activeAura = v.isEmpty() ? null : v;
                    case "v" -> {
                        for (String t : v.split(",")) {
                            int c = t.indexOf(':');
                            if (c > 0) d.levels.put(t.substring(0, c), Integer.parseInt(t.substring(c + 1)));
                        }
                    }
                    case "h" -> {
                        for (String t : v.split(",")) {
                            int c = t.indexOf(':');
                            if (c <= 0) continue;
                            int slot = Integer.parseInt(t.substring(0, c));
                            if (slot >= 1 && slot < d.hot.length) d.hot[slot] = t.substring(c + 1);
                        }
                    }
                    default -> { }
                }
            } catch (NumberFormatException ignored) {
                // 깨진 항목은 무시
            }
        }
        return d;
    }

    private int spentPoints(PData d) {
        int sum = 0;
        for (int v : d.levels.values()) sum += v;
        return sum;
    }

    private int totalPoints(PData d) {
        int start = plugin.getConfig().getInt("skill-system.start-points", 1);
        int per = plugin.getConfig().getInt("skill-system.points-per-level", 1);
        return start + d.maxLevel * per + d.bonusPoints;
    }

    private int freePoints(PData d) {
        return totalPoints(d) - spentPoints(d);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        PData d = data(e.getPlayer());
        d.maxLevel = Math.max(d.maxLevel, e.getPlayer().getLevel());
        save(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        save(p);
        UUID id = p.getUniqueId();
        data.remove(id);
        buffs.remove(id);
        auraBuffs.remove(id);
        frenzy.remove(id);
        locks.remove(id);
        nextSwing.remove(id);
        castingUntil.remove(id);
        leapGuard.remove(id);
        lastLeftCast.remove(id);
        bonusCache.remove(id);
        gearCache.remove(id);
    }

    // ===================================================================
    //  장비 스킬 옵션 합산 (모든 스킬 / 직업 스킬 / 스킬 레벨 / 오라 / 발동 / 시전 속도)
    // ===================================================================

    private boolean isRpg(ItemStack it) {
        return it != null && !it.getType().isAir() && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(plugin.rarityKey, PersistentDataType.STRING);
    }

    private Gear gearOf(Player p) {
        int tick = Bukkit.getCurrentTick();
        Object[] c = gearCache.get(p.getUniqueId());
        if (c != null && (Integer) c[0] == tick) return (Gear) c[1];

        List<PersistentDataContainer> pdcs = new ArrayList<>();
        for (ItemStack a : p.getInventory().getArmorContents()) {
            if (isRpg(a)) pdcs.add(a.getItemMeta().getPersistentDataContainer());
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off.getType() == Material.SHIELD && isRpg(off)) pdcs.add(off.getItemMeta().getPersistentDataContainer());
        ItemStack main = p.getInventory().getItemInMainHand();
        if (isRpg(main)) pdcs.add(main.getItemMeta().getPersistentDataContainer());
        SetSystem.Bonus sb = plugin.setSystem() == null ? null : plugin.setSystem().bonusFor(p);
        if (sb != null) pdcs.add(sb.pdc);

        Gear g = new Gear();
        for (PersistentDataContainer pdc : pdcs) {
            g.all += (int) Math.round(pdc.getOrDefault(key("sk_all"), PersistentDataType.DOUBLE, 0.0));
            g.castSpeed += (int) Math.round(pdc.getOrDefault(key("cast_speed"), PersistentDataType.DOUBLE, 0.0));
            for (ClassDef cd : classes.values()) {
                int v = (int) Math.round(pdc.getOrDefault(key("sk_class_" + cd.id), PersistentDataType.DOUBLE, 0.0));
                if (v != 0) g.cls.merge(cd.id, v, Integer::sum);
            }
            for (SkillDef sd : skills.values()) {
                int v = (int) Math.round(pdc.getOrDefault(key("sk_lv_" + sd.id), PersistentDataType.DOUBLE, 0.0));
                if (v != 0) g.lv.merge(sd.id, v, Integer::sum);
                if (sd.kind.equals("aura")) {
                    int a = (int) Math.round(pdc.getOrDefault(key("au_" + sd.id), PersistentDataType.DOUBLE, 0.0));
                    if (a > 0) g.aura.merge(sd.id, a, Integer::sum);
                }
            }
            for (ProcDef pd : procs.values()) {
                int v = (int) Math.round(pdc.getOrDefault(key("pr_" + pd.id), PersistentDataType.DOUBLE, 0.0));
                if (v > 0) g.proc.merge(pd.id, v, Integer::sum);
            }
        }
        gearCache.put(p.getUniqueId(), new Object[]{tick, g});
        return g;
    }

    /** 스킬의 현재 유효 레벨 (투자 포인트 + 장비 보너스 + 전투 명령 같은 버프). 투자하지 않았으면 0. */
    private int level(Player p, PData d, SkillDef s) {
        int base = d.levels.getOrDefault(s.id, 0);
        if (base <= 0) return 0;
        Gear g = gearOf(p);
        int bonus = g.lv.getOrDefault(s.id, 0);
        if (!s.classId.isEmpty() && s.classId.equals(d.cls)) {
            bonus += g.all + g.cls.getOrDefault(s.classId, 0) + (int) Math.round(buffSum(p, "모든 스킬"));
        }
        return Math.max(1, base + bonus);
    }

    /** 활성 버프/오라가 주는 특정 옵션 합. (level() 에서 쓰므로 cached()를 부르지 않는다: 순환 방지) */
    private double buffSum(Player p, String optionName) {
        long now = System.currentTimeMillis();
        return buffSumIn(buffs.get(p.getUniqueId()), optionName, now)
                + buffSumIn(auraBuffs.get(p.getUniqueId()), optionName, now);
    }

    private double buffSumIn(Map<String, Buff> map, String optionName, long now) {
        if (map == null) return 0.0;
        double sum = 0.0;
        for (Buff b : map.values()) {
            if (b.until < now) continue;
            SkillDef s = skills.get(b.id);
            if (s == null) continue;
            ConfigurationSection bs = s.sec.getConfigurationSection("bonuses");
            if (bs == null || !bs.isSet(optionName)) continue;
            sum += scaled(bs, optionName, b.level, 0.0);
        }
        return sum;
    }

    // ===================================================================
    //  패시브 / 버프 / 오라 -> 합성 PDC (기존 전투 계산에 합산)
    // ===================================================================

    private boolean isWeapon(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || m == Material.MACE || m == Material.TRIDENT;
    }

    private boolean holdingWeaponType(Player p, String type) {
        Material m = p.getInventory().getItemInMainHand().getType();
        String n = m.name();
        return switch (type) {
            case "sword" -> n.endsWith("_SWORD");
            case "axe" -> n.endsWith("_AXE");
            case "mace" -> m == Material.MACE;
            default -> false;
        };
    }

    private void addBonuses(Player p, Map<String, Double> totals, SkillDef s, int lv) {
        String wt = s.sec.getString("weapon", "");
        if (!wt.isEmpty() && !holdingWeaponType(p, wt)) return;
        ConfigurationSection b = s.sec.getConfigurationSection("bonuses");
        if (b == null) return;
        double defScale = plugin.getConfig().getDouble("skill-system.defense-scale", 0.2);
        for (String name : b.getKeys(false)) {
            double v = scaled(b, name, lv, 0.0);
            if (name.equals("방어력 증가")) v *= defScale; // 원작 "방어력 %"는 피해 감소 %보다 훨씬 커서 비율을 곱한다
            if (v != 0) totals.merge(name, v, Double::sum);
        }
    }

    private void addBuffMap(Player p, Map<String, Double> totals, Map<String, Buff> map, long now) {
        if (map == null) return;
        for (Buff b : map.values()) {
            if (b.until < now) continue;
            SkillDef s = skills.get(b.id);
            if (s != null) addBonuses(p, totals, s, b.level);
        }
    }

    private Cached cached(Player p) {
        int tick = Bukkit.getCurrentTick();
        Cached c = bonusCache.get(p.getUniqueId());
        if (c != null && c.tick == tick) return c;

        Map<String, Double> totals = new LinkedHashMap<>();
        if (enabled()) {
            PData d = data(p);
            long now = System.currentTimeMillis();
            for (SkillDef s : skills.values()) {
                if (!s.kind.equals("passive")) continue;
                int lv = level(p, d, s);
                if (lv > 0) addBonuses(p, totals, s, lv);
            }
            addBuffMap(p, totals, buffs.get(p.getUniqueId()), now);
            addBuffMap(p, totals, auraBuffs.get(p.getUniqueId()), now);

            Frenzy f = frenzy.get(p.getUniqueId());
            if (f != null && f.until >= now && f.stacks > 0) {
                SkillDef fs = skills.get(f.skillId);
                if (fs != null) {
                    double max = scaled(fs.sec, "attack-speed-max", f.level, 0.0);
                    int cap = Math.max(1, fs.sec.getInt("stack-max", 5));
                    double v = max * Math.min(f.stacks, cap) / cap;
                    totals.merge("공격 속도 증가", v, Double::sum);
                    totals.merge("이동 속도", v * 0.5, Double::sum);
                }
            }
            double defCap = plugin.getConfig().getDouble("skill-system.defense-cap", 60.0);
            Double def = totals.get("방어력 증가");
            if (def != null && def > defCap) totals.put("방어력 증가", defCap);
        }
        PersistentDataContainer pdc = null;
        if (!totals.isEmpty()) {
            ItemMeta scratch = new ItemStack(Material.STONE).getItemMeta();
            for (Map.Entry<String, Double> t : totals.entrySet()) {
                plugin.applyOptionToPdc(scratch, t.getKey(), (int) Math.round(t.getValue()));
            }
            pdc = scratch.getPersistentDataContainer();
        }
        c = new Cached(tick, pdc, totals);
        bonusCache.put(p.getUniqueId(), c);
        return c;
    }

    /** 전투/능력치 계산에 합산할 스킬 보너스 PDC. 없으면 null. */
    PersistentDataContainer bonusPdc(Player p) {
        return cached(p).pdc;
    }

    /** 스킬이 주는 최대 마나 % (전투 지시). StatSystem 이 최대 마나 계산에 쓴다. */
    double manaPct(Player p) {
        return cached(p).totals.getOrDefault("최대 마나%", 0.0);
    }

    private void refreshAttributes(Player p) {
        Map<String, Double> t = cached(p).totals;
        setModifier(p, VersionCompat.MAX_HEALTH, "skill_maxhealth", t.getOrDefault("최대 생명력", 0.0),
                AttributeModifier.Operation.ADD_NUMBER);
        setModifier(p, VersionCompat.MAX_HEALTH, "skill_maxhealth_pct", t.getOrDefault("최대 생명력%", 0.0) / 100.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        setModifier(p, VersionCompat.ATTACK_SPEED, "skill_atkspeed", 4.0 * t.getOrDefault("공격 속도 증가", 0.0) / 100.0,
                AttributeModifier.Operation.ADD_NUMBER);
        setModifier(p, VersionCompat.MOVEMENT_SPEED, "skill_movespeed", t.getOrDefault("이동 속도", 0.0) / 100.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }

    private void setModifier(Player p, Attribute attribute, String keyName, double amount,
                             AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attribute);
        if (inst == null) return;
        NamespacedKey k = key(keyName);
        AttributeModifier existing = null;
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (k.equals(m.getKey())) {
                existing = m;
                break;
            }
        }
        if (existing != null) {
            if (existing.getAmount() == amount && existing.getOperation() == op) return;
            inst.removeModifier(existing);
        }
        if (amount != 0.0) inst.addModifier(new AttributeModifier(k, amount, op));
    }

    // ===================================================================
    //  주기 작업
    // ===================================================================

    /** 0.5초: 레벨 추적, 속성 갱신, 만료 정리 */
    private void fastTick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PData d = data(p);
            if (p.getLevel() > d.maxLevel) {
                d.maxLevel = p.getLevel();
                save(p);
            }
            expire(buffs.get(p.getUniqueId()), now);
            expire(auraBuffs.get(p.getUniqueId()), now);
            Frenzy f = frenzy.get(p.getUniqueId());
            if (f != null && f.until < now) frenzy.remove(p.getUniqueId());
            bonusCache.remove(p.getUniqueId());
            refreshAttributes(p);
        }
        leapGuard.values().removeIf(t -> t < now);
        feared.values().removeIf(t -> t < now);
    }

    private void expire(Map<String, Buff> map, long now) {
        if (map == null) return;
        map.values().removeIf(b -> b.until < now);
    }

    /** 1초: 오라 적용/피해, 저주 정리/표시 */
    private void slowTick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) continue;
            PData d = data(p);
            Map<String, Integer> auras = new HashMap<>(gearOf(p).aura);
            if (d.activeAura != null) {
                SkillDef s = skills.get(d.activeAura);
                if (s != null && s.kind.equals("aura")) {
                    int lv = level(p, d, s);
                    if (lv > 0) auras.merge(s.id, lv, Math::max);
                }
            }
            for (Map.Entry<String, Integer> a : auras.entrySet()) {
                SkillDef s = skills.get(a.getKey());
                if (s == null) continue;
                runAura(p, s, a.getValue(), now);
            }
        }

        Iterator<Map.Entry<UUID, Map<String, Curse>>> it = curses.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Map<String, Curse>> e = it.next();
            Entity ent = Bukkit.getEntity(e.getKey());
            e.getValue().values().removeIf(c -> c.until < now);
            if (ent == null || !ent.isValid() || ent.isDead() || e.getValue().isEmpty()) {
                it.remove();
                continue;
            }
            Location top = ent.getLocation().add(0, ent instanceof LivingEntity le ? le.getHeight() + 0.3 : 1.5, 0);
            ent.getWorld().spawnParticle(Particle.WITCH, top, 6, 0.25, 0.15, 0.25, 0.0);
        }
    }

    private void runAura(Player p, SkillDef s, int lv, long now) {
        double radius = radius(p, s, lv, 10.0);
        boolean selfOnly = s.sec.getBoolean("self-only", false);
        List<Player> recipients = new ArrayList<>();
        recipients.add(p);
        if (!selfOnly) {
            for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
                if (e instanceof Player o && !o.isDead() && o.getLocation().distanceSquared(p.getLocation()) <= radius * radius) {
                    recipients.add(o);
                }
            }
        }
        if (s.sec.isConfigurationSection("bonuses")) {
            for (Player r : recipients) {
                Map<String, Buff> map = auraBuffs.computeIfAbsent(r.getUniqueId(), k -> new HashMap<>());
                Buff old = map.get(s.id);
                if (old == null || old.until < now || old.level <= lv) {
                    map.put(s.id, new Buff(s.id, lv, now + 2500L));
                }
            }
        }

        double dmg = val(p, s, "damage", lv, 0.0);
        double slowAmp = val(p, s, "slow-amp", lv, 0.0);
        if (dmg > 0 || slowAmp > 0) {
            for (LivingEntity t : around(p.getLocation(), radius, p)) {
                if (dmg > 0) pureHit(p, t, dmg);
                if (slowAmp > 0 && t.isValid() && !t.isDead()) {
                    t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, (int) Math.max(0, slowAmp - 1), false, false));
                }
            }
        }
        String element = s.sec.getString("element", "");
        Particle particle = switch (element) {
            case "fire" -> Particle.FLAME;
            case "cold" -> Particle.SNOWFLAKE;
            default -> Particle.ENCHANT;
        };
        for (int i = 0; i < 16; i++) {
            double ang = Math.PI * 2 * i / 16.0;
            Location l = p.getLocation().add(Math.cos(ang) * Math.min(radius, 4.0), 0.2, Math.sin(ang) * Math.min(radius, 4.0));
            p.getWorld().spawnParticle(particle, l, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    // ===================================================================
    //  저주 (몹에 붙는 상태)
    // ===================================================================

    /**
     * 대상에 걸린 저주 수치.
     * type: amplify(물리 피해 증가) / lower_resist(저항 감소) / weaken(몹 피해 감소) / reflect(반사) / defense_down(대상 방어력 감소)
     * 없으면 0.
     */
    double curseAmount(Entity e, String type) {
        Map<String, Curse> m = curses.get(e.getUniqueId());
        if (m == null) return 0.0;
        Curse c = m.get(type);
        if (c == null || c.until < System.currentTimeMillis()) return 0.0;
        return c.amount;
    }

    private void applyCurse(LivingEntity t, SkillDef s, int lv) {
        for (String section : new String[]{"curse", "curse2"}) {
            ConfigurationSection c = s.sec.getConfigurationSection(section);
            if (c == null) continue;
            String type = c.getString("type", "amplify");
            double amount = scaled(c, "amount", lv, 0.0);
            double durSec = scaled(c, "duration", lv, 6.0);
            long now = System.currentTimeMillis();
            long until = now + (long) (durSec * 1000.0);
            if (type.equals("slow")) {
                t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) (durSec * 20), (int) Math.max(0, amount - 1), false, true));
                continue;
            }
            Map<String, Curse> m = curses.computeIfAbsent(t.getUniqueId(), k -> new HashMap<>());
            Curse old = m.get(type);
            if (old != null && old.until >= now && old.amount > amount) {
                m.put(type, new Curse(old.amount, Math.max(old.until, until))); // 더 강한 저주는 유지하고 시간만 연장
            } else {
                m.put(type, new Curse(amount, until));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCursedMobHit(EntityDamageByEntityEvent e) {
        LivingEntity attacker = mobAttacker(e.getDamager());
        if (attacker == null) return;
        double w = curseAmount(attacker, "weaken");
        if (w > 0) e.setDamage(e.getDamage() * Math.max(0.1, 1.0 - w / 100.0));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCursedMobReflect(EntityDamageByEntityEvent e) {
        if (reflecting) return;
        LivingEntity attacker = mobAttacker(e.getDamager());
        if (attacker == null) return;
        double r = curseAmount(attacker, "reflect");
        if (r <= 0) return;
        double dealt = e.getFinalDamage();
        if (dealt <= 0) return;
        reflecting = true;
        try {
            attacker.damage(dealt * r / 100.0);
        } finally {
            reflecting = false;
        }
    }

    private LivingEntity mobAttacker(Entity damager) {
        if (damager instanceof Player) return null;
        if (damager instanceof LivingEntity le) return le;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof LivingEntity sh && !(sh instanceof Player)) return sh;
        return null;
    }

    /** 공포에 걸린 몹은 도망치는 동안 플레이어를 목표로 삼지 않는다. */
    @EventHandler(ignoreCancelled = true)
    public void onMobTarget(EntityTargetEvent e) {
        if (e.getTarget() == null) return;
        Long until = feared.get(e.getEntity().getUniqueId());
        if (until != null && until > System.currentTimeMillis()) e.setCancelled(true);
    }

    // ===================================================================
    //  장비 발동 옵션 (타격 시 / 피격 시 확률로 스킬 시전)
    // ===================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProcTrigger(EntityDamageByEntityEvent e) {
        if (!enabled() || casting || pure || reflecting) return;
        Entity dm = e.getDamager();
        Player attackerPlayer = null;
        if (dm instanceof Player pl) attackerPlayer = pl;
        else if (dm instanceof Projectile pr && pr.getShooter() instanceof Player pl2) attackerPlayer = pl2;

        if (attackerPlayer != null && e.getEntity() instanceof LivingEntity t && validTarget(attackerPlayer, t)) {
            runProcs(attackerPlayer, "hit", t);
        }
        if (e.getEntity() instanceof Player victim) {
            LivingEntity attacker = mobAttacker(dm);
            if (attacker != null && validTarget(victim, attacker)) runProcs(victim, "struck", attacker);
        }
    }

    private void runProcs(Player p, String trigger, LivingEntity target) {
        Gear g = gearOf(p);
        if (g.proc.isEmpty()) return;
        for (Map.Entry<String, Integer> en : g.proc.entrySet()) {
            ProcDef pd = procs.get(en.getKey());
            if (pd == null || !pd.trigger.equals(trigger)) continue;
            if (random.nextDouble() * 100.0 >= en.getValue()) continue;
            SkillDef s = skills.get(pd.cast);
            if (s == null) continue;
            castAt(p, s, pd.level, target);
            plugin.getLogger().info(String.format(Locale.ROOT, "[스킬 발동] %s | %s | %s %d레벨 (확률 %d%%) -> %s",
                    p.getName(), trigger.equals("hit") ? "타격 시" : "피격 시", s.name, pd.level, en.getValue(), target.getType().name()));
        }
    }

    /** 장비 발동용 시전: 마나/속도 제한 없이 대상 위치에서 효과만. */
    private void castAt(Player p, SkillDef s, int lv, LivingEntity target) {
        switch (s.kind) {
            case "curse" -> applyCurseArea(p, s, lv, target.getLocation());
            case "aoe" -> doAoe(p, s, lv, target.getLocation());
            case "buff" -> doBuff(p, s, lv);
            case "melee" -> hit(p, target, skillDamage(p, s, lv));
            default -> { }
        }
    }

    // ===================================================================
    //  대상 찾기 / 피해
    // ===================================================================

    private boolean validTarget(Player p, LivingEntity t) {
        if (t == p || t.isDead() || !t.isValid()) return false;
        if (t instanceof Player) return plugin.getConfig().getBoolean("skill-system.hit-players", false);
        if (t instanceof ArmorStand || t instanceof Villager || t instanceof WanderingTrader) return false;
        if (t instanceof Tameable tm && tm.isTamed()) return false;
        return true;
    }

    private LivingEntity findTarget(Player p, double range) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(), range, 0.4,
                en -> en instanceof LivingEntity le && validTarget(p, le));
        if (r == null || !(r.getHitEntity() instanceof LivingEntity le)) return null;
        return le;
    }

    private List<LivingEntity> around(Location center, double radius, Player caster) {
        List<LivingEntity> list = new ArrayList<>();
        if (center.getWorld() == null) return list;
        for (Entity e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (e instanceof LivingEntity le && validTarget(caster, le)
                    && le.getLocation().distanceSquared(center) <= radius * radius) {
                list.add(le);
            }
        }
        return list;
    }

    private LivingEntity nearestOther(Player p, LivingEntity first, double radius) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity o : around(first.getLocation(), radius, p)) {
            if (o == first) continue;
            double d = o.getLocation().distanceSquared(first.getLocation());
            if (d < bestD) {
                bestD = d;
                best = o;
            }
        }
        return best;
    }

    /** 플레이어 공격력 속성(무기 포함). */
    private double attackPower(Player p) {
        AttributeInstance atk = p.getAttribute(VersionCompat.ATTACK_DAMAGE);
        return atk == null ? 1.0 : Math.max(1.0, atk.getValue());
    }

    /** 스킬 피해 = 공격력 x damage-pct% + flat-damage. 이후 기존 파이프라인이 % 증가/저항 등을 적용한다. */
    private double skillDamage(Player p, SkillDef s, int lv) {
        return attackPower(p) * val(p, s, "damage-pct", lv, 100.0) / 100.0 + val(p, s, "flat-damage", lv, 0.0);
    }

    private void hit(Player p, LivingEntity t, double dmg) {
        if (t.isDead() || !t.isValid()) return;
        boolean prev = casting;
        casting = true;
        try {
            t.setNoDamageTicks(0);
            t.setLastDamage(0.0); // 0.7.2: 연타 때 '직전 피해와의 차이만 적용' 규칙으로 피해가 깎이는 것을 막는다
            double before = t.getHealth();
            t.damage(dmg, p);
            if (plugin.getConfig().getBoolean("skill-system.debug", false)) {
                plugin.getLogger().info(String.format(Locale.ROOT, "[스킬 피해] %s -> %s | 요청 %.1f | 체력 변화 %.1f | 직전피해 %.1f",
                        p.getName(), t.getType().name(), dmg, before - t.getHealth(), t.getLastDamage()));
            }
        } finally {
            casting = prev;
        }
    }

    private void pureHit(Player p, LivingEntity t, double dmg) {
        if (t.isDead() || !t.isValid()) return;
        boolean prevPure = pure;
        boolean prevCasting = casting;
        pure = true;
        casting = true;
        try {
            t.setNoDamageTicks(0);
            t.setLastDamage(0.0); // 0.7.2: 연타 때 '직전 피해와의 차이만 적용' 규칙으로 피해가 깎이는 것을 막는다
            t.damage(dmg, p);
        } finally {
            pure = prevPure;
            casting = prevCasting;
        }
    }

    private void knock(Location from, LivingEntity t, double power) {
        if (power <= 0 || t.isDead()) return;
        Vector v = t.getLocation().toVector().subtract(from.toVector());
        v.setY(0);
        if (v.lengthSquared() < 0.0001) return;
        v.normalize().multiply(power).setY(0.3);
        t.setVelocity(t.getVelocity().add(v));
    }

    private void stun(LivingEntity t, double seconds) {
        if (seconds <= 0 || t.isDead()) return;
        t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) (seconds * 20), 6, false, true));
    }

    // ===================================================================
    //  스킬 시전
    // ===================================================================

    private void msg(Player p, String text) {
        p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(text));
    }

    /** 스킬을 쓸 수 있는 자세인가: 무기가 1번 칸이고 손에 들고 있는 RPG 무기. */
    private boolean weaponReady(Player p) {
        if (!enabled()) return false;
        if (plugin.getConfig().getBoolean("skill-system.require-slot-one", true)
                && p.getInventory().getHeldItemSlot() != 0) return false;
        if (plugin.getConfig().getBoolean("skill-system.require-rpg-weapon", true)
                && !isRpg(p.getInventory().getItemInMainHand())) return false;
        return true;
    }

    /** 공격 속도(초당 공격 횟수)로 정해지는 공격 스킬 간 최소 간격(ms). */
    private long swingIntervalMs(Player p) {
        AttributeInstance a = p.getAttribute(VersionCompat.ATTACK_SPEED);
        double rate = a == null ? 4.0 : Math.max(0.1, a.getValue());
        return Math.max(100L, Math.min(2000L, (long) (1000.0 / rate)));
    }

    /** 스킬에 커스텀 소리(sounds)가 설정돼 있는가. */
    private boolean hasCustomSound(SkillDef s) {
        return !s.sec.getStringList("sounds").isEmpty();
    }

    /** 시전 시작 소리: sounds 목록에서 무작위로 하나(리소스팩 소리 키). 없으면 기본 효과음. */
    private void playCastSound(Player p, SkillDef s) {
        List<String> list = s.sec.getStringList("sounds");
        if (list.isEmpty()) {
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.7f, 1.2f);
            return;
        }
        String key = list.get(random.nextInt(list.size()));
        float vol = (float) s.sec.getDouble("sound-volume", 1.0);
        float pitch = (float) s.sec.getDouble("sound-pitch", 1.0);
        p.getWorld().playSound(p.getLocation(), key, org.bukkit.SoundCategory.PLAYERS, vol, pitch);
    }

    private Result tryCast(Player p, SkillDef s, LivingEntity forced, boolean quiet) {
        PData d = data(p);
        int lv = level(p, d, s);
        if (lv <= 0) {
            if (!quiet) msg(p, ChatColor.RED + s.name + "을(를) 배우지 않았습니다.");
            return Result.FAIL;
        }
        if (s.kind.equals("passive")) return Result.FAIL;
        if (s.kind.equals("display")) {
            if (!quiet) msg(p, ChatColor.GRAY + s.name + ": 아직 효과가 없는 스킬입니다. (표시만)");
            return Result.FAIL;
        }

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        String curLock = locks.get(id);
        if (curLock != null && !s.lock.isEmpty() && curLock.equals(s.lock)) return Result.WAIT;
        Long cu = castingUntil.get(id);
        if (cu != null && cu > now) return Result.WAIT;
        if (s.speed.equals("attack")) {
            Long ns = nextSwing.get(id);
            if (ns != null && ns > now) return Result.WAIT;
        }

        StatSystem ss = plugin.statSystem();
        StatSystem.Stats st = ss.collect(p);
        double max = ss.maxMana(st);
        double cur = ss.getMana(p);
        if (cur < 0) cur = max;
        double cost = val(p, s, "mana", lv, 0.0);
        if (cost > 0 && cur < cost) {
            if (!quiet) msg(p, ChatColor.AQUA + "마나가 부족합니다. (" + (int) Math.ceil(cost) + " 필요)");
            return Result.FAIL;
        }

        if (s.speed.equals("cast")) {
            double base = plugin.getConfig().getDouble("skill-system.cast-time", 0.8);
            double fcr = gearOf(p).castSpeed;
            long delayMs = (long) (base * 1000.0 / (1.0 + fcr / 100.0));
            if (delayMs > 50L) {
                castingUntil.put(id, now + delayMs);
                if (cost > 0) ss.setMana(p, cur - cost);
                msg(p, ChatColor.GOLD + s.name + ChatColor.GRAY + " 시전 중...");
                playCastSound(p, s);
                final int lvF = lv;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    castingUntil.remove(id);
                    if (!p.isOnline() || p.isDead()) return;
                    execute(p, s, lvF, null, true);
                }, Math.max(1L, delayMs / 50L));
                return Result.OK;
            }
        }

        if (s.speed.equals("cast")) playCastSound(p, s);
        if (!execute(p, s, lv, forced, quiet)) return Result.FAIL;

        if (cost > 0) ss.setMana(p, cur - cost);
        if (s.speed.equals("attack")) nextSwing.put(id, now + swingIntervalMs(p));
        msg(p, ChatColor.GOLD + s.name + ChatColor.GRAY + " Lv" + lv
                + (cost > 0 ? ChatColor.AQUA + "  마나 -" + (int) Math.ceil(cost) : ""));
        return Result.OK;
    }

    private boolean execute(Player p, SkillDef s, int lv, LivingEntity forced, boolean quiet) {
        return switch (s.kind) {
            case "melee" -> doMelee(p, s, lv, forced, quiet);
            case "aoe" -> {
                doAoe(p, s, lv, p.getLocation());
                yield true;
            }
            case "fear" -> doFear(p, s, lv);
            case "whirlwind" -> doWhirlwind(p, s, lv);
            case "leap" -> doLeap(p, s, lv);
            case "leap_attack" -> doLeapAttack(p, s, lv, forced, quiet);
            case "buff" -> {
                doBuff(p, s, lv);
                yield true;
            }
            case "curse" -> doCurse(p, s, lv, forced, quiet);
            case "aura" -> doAuraToggle(p, s);
            default -> false;
        };
    }

    private void unlock(Player p, String group) {
        if (group.equals(locks.get(p.getUniqueId()))) locks.remove(p.getUniqueId());
    }

    private boolean doMelee(Player p, SkillDef s, int lv, LivingEntity forced, boolean quiet) {
        boolean offWeapon = isWeapon(p.getInventory().getItemInOffHand().getType());
        if (s.sec.getBoolean("requires-offhand", false) && !offWeapon) {
            if (!quiet) msg(p, ChatColor.RED + s.name + ": 왼손에도 무기를 들어야 합니다.");
            return false;
        }
        double range = val(p, s, "range", lv, 4.5);
        LivingEntity t = forced != null ? forced : findTarget(p, range);
        if (t == null) {
            if (!quiet) msg(p, ChatColor.GRAY + "대상이 없습니다.");
            return false;
        }

        // 공격 순서: 이중 타격류는 왼손 무기가 있으면 두 대상에게 한 번씩, 없으면 한 대상에게 연속으로.
        List<LivingEntity> seq = new ArrayList<>();
        int hits = Math.max(1, s.sec.getInt("hits", 1));
        if (s.sec.getBoolean("dual-targets", false) && offWeapon) {
            seq.add(t);
            LivingEntity second = nearestOther(p, t, 4.0);
            seq.add(second != null ? second : t);
        } else {
            for (int i = 0; i < hits; i++) seq.add(t);
        }

        final boolean hasPhys = s.sec.contains("damage-pct");
        final double dmg = hasPhys ? skillDamage(p, s, lv) : 0.0;
        final double magic = attackPower(p) * val(p, s, "magic-pct", lv, 0.0) / 100.0; // 마법(비전) 피해: 방어/저항 무시
        final double kb = val(p, s, "knockback", lv, 0.0);
        final double stunSec = val(p, s, "stun-seconds", lv, 0.0);
        final Location origin = p.getLocation();

        for (int i = 0; i < seq.size(); i++) {
            final LivingEntity target = seq.get(i);
            Runnable r = () -> {
                if (!p.isOnline() || p.isDead() || !target.isValid() || target.isDead()) return;
                if (hasPhys) hit(p, target, dmg);
                if (magic > 0) pureHit(p, target, magic);
                target.getWorld().spawnParticle(Particle.SWEEP_ATTACK, target.getLocation().add(0, 1, 0), 1);
                target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.9f);
            };
            if (i == 0) r.run();
            else Bukkit.getScheduler().runTaskLater(plugin, r, 3L * i);
        }
        knock(origin, t, kb);
        stun(t, stunSec);

        // 집중 공격/버서크: 짧은 자가 버프(방어력 보너스/페널티)
        double sb = s.sec.getDouble("self-buff-seconds", 0.0);
        if (sb > 0) {
            buffs.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>())
                    .put(s.id, new Buff(s.id, lv, System.currentTimeMillis() + (long) (sb * 1000.0)));
            bonusCache.remove(p.getUniqueId());
            refreshAttributes(p);
        }

        // 광분: 적중마다 속도 보너스 누적
        if (s.sec.getBoolean("frenzy-stack", false) || s.sec.contains("attack-speed-max")) { // 0.7.4: attack-speed-max 가 있으면 중첩
            Frenzy f = frenzy.computeIfAbsent(p.getUniqueId(), k -> new Frenzy());
            long now = System.currentTimeMillis();
            if (f.until < now) f.stacks = 0;
            f.skillId = s.id;
            f.level = lv;
            f.stacks = Math.min(Math.max(1, s.sec.getInt("stack-max", 5)), f.stacks + seq.size());
            f.until = now + (long) (val(p, s, "stack-seconds", lv, 6.0) * 1000.0);
            bonusCache.remove(p.getUniqueId());
            refreshAttributes(p);
        }
        return true;
    }

    private void doAoe(Player p, SkillDef s, int lv, Location center) {
        double radius = radius(p, s, lv, 5.0);
        boolean hasDmg = s.sec.contains("damage-pct");
        double dmg = hasDmg ? skillDamage(p, s, lv) : 0.0;
        boolean flat = s.sec.contains("damage-min");
        double fmin = val(p, s, "damage-min", lv, 0.0);
        double frange = scaled(s.sec, "damage-range", lv, 10.0);
        double bonusPct = val(p, s, "damage-bonus-pct", lv, 0.0);
        double kb = val(p, s, "knockback", lv, 0.0);
        double stunSec = val(p, s, "stun-seconds", lv, 0.0);
        for (LivingEntity t : around(center, radius, p)) {
            if (hasDmg) hit(p, t, dmg);
            if (flat) pureHit(p, t, (fmin + random.nextDouble() * frange) * (1.0 + bonusPct / 100.0));
            knock(center, t, kb);
            stun(t, stunSec);
        }
        World w = center.getWorld();
        if (w != null) {
            double ring = Math.min(radius, 8.0);
            for (int i = 0; i < 32; i++) {
                double ang = Math.PI * 2 * i / 32.0;
                w.spawnParticle(Particle.CLOUD, center.clone().add(Math.cos(ang) * ring, 0.3, Math.sin(ang) * ring), 1, 0, 0, 0, 0.02);
            }
            if (!hasCustomSound(s)) w.playSound(center, Sound.ENTITY_RAVAGER_ROAR, 1.0f, 1.0f);
        }
    }

    /** 포효: 주변 몹이 일정 시간 동안 시전자에게서 멀리 도망친다. */
    private boolean doFear(Player p, SkillDef s, int lv) {
        double radius = radius(p, s, lv, 8.0);
        final double dist = val(p, s, "flee-dist-yd", lv, 16.0) * lengthScale();
        final double secs = val(p, s, "flee-seconds", lv, 3.0);
        final Location from = p.getLocation();
        final long until = System.currentTimeMillis() + (long) (secs * 1000.0);
        for (LivingEntity t : around(from, radius, p)) {
            if (!(t instanceof Mob m)) continue;
            feared.put(m.getUniqueId(), until);
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!m.isValid() || m.isDead() || System.currentTimeMillis() > until) {
                        feared.remove(m.getUniqueId());
                        cancel();
                        return;
                    }
                    m.setTarget(null);
                    Vector away = m.getLocation().toVector().subtract(from.toVector());
                    away.setY(0);
                    if (away.lengthSquared() < 0.01) away = new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
                    away.normalize().multiply(Math.max(4.0, dist));
                    m.getPathfinder().moveTo(m.getLocation().add(away), 1.4);
                }
            }.runTaskTimer(plugin, 0L, 10L);
        }
        World w = p.getWorld();
        w.spawnParticle(Particle.CLOUD, from.clone().add(0, 1, 0), 40, radius * 0.3, 0.5, radius * 0.3, 0.05);
        w.playSound(from, Sound.ENTITY_RAVAGER_ROAR, 1.2f, 0.8f);
        return true;
    }

    private boolean doWhirlwind(Player p, SkillDef s, int lv) {
        final double radius = radius(p, s, lv, 3.0);
        final double dmg = skillDamage(p, s, lv);
        final int duration = (int) (val(p, s, "duration", lv, 2.0) * 20.0);
        final int interval = Math.max(1, s.sec.getInt("interval", 5));
        final int startSlot = p.getInventory().getHeldItemSlot();
        final String group = s.lock.isEmpty() ? "whirl" : s.lock;
        locks.put(p.getUniqueId(), group);

        // 앞으로 살짝 돌진
        Vector dir = p.getLocation().getDirection();
        dir.setY(0);
        if (dir.lengthSquared() > 0.0001) {
            dir.normalize().multiply(val(p, s, "dash", lv, 0.8)).setY(0.1);
            p.setVelocity(dir);
        }

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                // 지속 중 핫바 칸을 바꾸면(무기를 놓으면) 멈춘다.
                if (!p.isOnline() || p.isDead() || t >= duration || p.getInventory().getHeldItemSlot() != startSlot) {
                    unlock(p, group);
                    cancel();
                    return;
                }
                Location c = p.getLocation();
                for (int i = 0; i < 12; i++) {
                    double ang = Math.PI * 2 * i / 12.0 + t * 0.6;
                    p.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                            c.clone().add(Math.cos(ang) * radius * 0.8, 1.0, Math.sin(ang) * radius * 0.8), 1);
                }
                if (t % interval == 0) {
                    for (LivingEntity target : around(c, radius, p)) {
                        hit(p, target, dmg);
                    }
                    p.getWorld().playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.0f);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    /** 도약: 뛰어올라 착지하면 주변 적을 밀쳐낸다. 착지 전에는 도약류를 다시 쓸 수 없다. */
    private boolean doLeap(Player p, SkillDef s, int lv) {
        double power = val(p, s, "power", lv, 1.2);
        Vector dir = p.getLocation().getDirection();
        dir.setY(0);
        if (dir.lengthSquared() < 0.0001) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(power).setY(0.65);
        p.setVelocity(dir);
        final String group = s.lock.isEmpty() ? "air" : s.lock;
        locks.put(p.getUniqueId(), group);
        leapGuard.put(p.getUniqueId(), System.currentTimeMillis() + 5000L);
        final double radius = radius(p, s, lv, 3.0);
        final double kb = val(p, s, "knockback", lv, 1.0);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                if (!p.isOnline() || p.isDead()) {
                    unlock(p, group);
                    cancel();
                    return;
                }
                if ((t > 4 && p.isOnGround()) || t > 40) {
                    cancel();
                    unlock(p, group);
                    Location c = p.getLocation();
                    for (LivingEntity target : around(c, radius, p)) {
                        knock(c, target, kb);
                    }
                    p.setFallDistance(0f);
                    leapGuard.put(p.getUniqueId(), System.currentTimeMillis() + 800L);
                    c.getWorld().spawnParticle(Particle.CLOUD, c, 24, Math.min(radius, 6.0) * 0.4, 0.1, Math.min(radius, 6.0) * 0.4, 0.05);
                    c.getWorld().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
                }
            }
        }.runTaskTimer(plugin, 2L, 1L);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f);
        return true;
    }

    /** 도약 공격: 대상 적에게 도약해 신속하게 공격한다. 착지 전에는 도약류를 다시 쓸 수 없다. */
    private boolean doLeapAttack(Player p, SkillDef s, int lv, LivingEntity forced, boolean quiet) {
        double range = val(p, s, "range-yd", lv, 24.0) * lengthScale();
        final LivingEntity target = forced != null ? forced : findTarget(p, range);
        if (target == null) {
            if (!quiet) msg(p, ChatColor.GRAY + "대상이 없습니다.");
            return false;
        }
        Vector flat = target.getLocation().toVector().subtract(p.getLocation().toVector());
        flat.setY(0);
        double dist = flat.length();
        if (dist < 0.01) flat = p.getLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 0.0001) flat = new Vector(0, 0, 1);
        flat.normalize().multiply(Math.min(1.8, 0.5 + dist * 0.18)).setY(0.5);
        p.setVelocity(flat);

        final String group = s.lock.isEmpty() ? "air" : s.lock;
        locks.put(p.getUniqueId(), group);
        leapGuard.put(p.getUniqueId(), System.currentTimeMillis() + 5000L);
        final double dmg = skillDamage(p, s, lv);
        new BukkitRunnable() {
            int t = 0;
            boolean done = false;

            @Override
            public void run() {
                t++;
                if (!p.isOnline() || p.isDead()) {
                    unlock(p, group);
                    cancel();
                    return;
                }
                boolean near = target.isValid() && p.getLocation().distanceSquared(target.getLocation()) < 6.25;
                if (!done && t > 3 && (near || p.isOnGround() || t > 30)) {
                    done = true;
                    if (target.isValid() && !target.isDead()
                            && p.getLocation().distanceSquared(target.getLocation()) < 25.0) {
                        hit(p, target, dmg);
                        target.getWorld().spawnParticle(Particle.SWEEP_ATTACK, target.getLocation().add(0, 1, 0), 1);
                        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.9f);
                    }
                }
                if ((t > 4 && p.isOnGround()) || t > 40) {
                    cancel();
                    unlock(p, group);
                    p.setFallDistance(0f);
                    leapGuard.put(p.getUniqueId(), System.currentTimeMillis() + 800L);
                }
            }
        }.runTaskTimer(plugin, 2L, 1L);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f);
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeapFall(EntityDamageEvent e) {
        if (e.getCause() != DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        Long g = leapGuard.get(p.getUniqueId());
        if (g != null && g > System.currentTimeMillis()) e.setCancelled(true);
    }

    private void doBuff(Player p, SkillDef s, int lv) {
        double durSec = val(p, s, "duration", lv, 30.0);
        double radius = radius(p, s, lv, 0.0);
        long until = System.currentTimeMillis() + (long) (durSec * 1000.0);
        List<Player> targets = new ArrayList<>();
        targets.add(p);
        if (radius > 0) {
            for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
                if (e instanceof Player o && !o.isDead()) targets.add(o);
            }
        }
        for (Player t : targets) {
            buffs.computeIfAbsent(t.getUniqueId(), k -> new HashMap<>()).put(s.id, new Buff(s.id, lv, until));
            bonusCache.remove(t.getUniqueId());
            refreshAttributes(t);
            t.getWorld().spawnParticle(Particle.ENCHANT, t.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.5);
            if (t != p) msg(t, ChatColor.GREEN + p.getName() + "의 " + s.name + " 효과를 받았습니다.");
        }
        if (!hasCustomSound(s)) p.getWorld().playSound(p.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 1.2f);
    }

    private boolean doCurse(Player p, SkillDef s, int lv, LivingEntity forced, boolean quiet) {
        // center: self -> 시전자 주변 (도발, 전투의 함성)
        if (s.sec.getString("center", "").equals("self")) {
            applyCurseArea(p, s, lv, p.getLocation());
            return true;
        }
        double range = val(p, s, "range", lv, 20.0);
        LivingEntity t = forced != null ? forced : findTarget(p, range);
        Location center;
        if (t != null) {
            center = t.getLocation();
        } else {
            Location eye = p.getEyeLocation();
            center = eye.clone().add(eye.getDirection().multiply(6.0));
        }
        boolean any = applyCurseArea(p, s, lv, center);
        if (!any && !quiet) msg(p, ChatColor.GRAY + "저주를 걸 대상이 없습니다.");
        return any;
    }

    private boolean applyCurseArea(Player p, SkillDef s, int lv, Location center) {
        double radius = radius(p, s, lv, 4.0);
        boolean aggro = s.sec.getBoolean("aggro", false);
        List<LivingEntity> list = around(center, radius, p);
        for (LivingEntity t : list) {
            applyCurse(t, s, lv);
            if (aggro && t instanceof Mob m) {
                Long fu = feared.get(m.getUniqueId());
                if (fu == null || fu < System.currentTimeMillis()) m.setTarget(p);
            }
        }
        World w = center.getWorld();
        if (w != null && !list.isEmpty()) {
            w.spawnParticle(Particle.WITCH, center.clone().add(0, 1, 0), 30, Math.min(radius, 8.0) * 0.4, 0.5, Math.min(radius, 8.0) * 0.4, 0.0);
            w.playSound(center, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.8f, 1.0f);
        }
        return !list.isEmpty();
    }

    private boolean doAuraToggle(Player p, SkillDef s) {
        PData d = data(p);
        if (s.id.equals(d.activeAura)) {
            d.activeAura = null;
            msg(p, ChatColor.GRAY + s.name + " 해제");
        } else {
            d.activeAura = s.id;
            msg(p, ChatColor.GREEN + s.name + " 활성화");
        }
        save(p);
        return true;
    }

    // ===================================================================
    //  입력: 좌클릭 / 우클릭 / 숫자키
    // ===================================================================

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        boolean left = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK;
        if (!right && !left) return;
        Player p = e.getPlayer();
        if (!weaponReady(p)) return;
        if (right && a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && e.getClickedBlock().getType().isInteractable() && !p.isSneaking()) return;

        PData d = data(p);
        if (d.cls == null) return;
        String id = right ? d.right : d.left;
        if (id == null) return;
        SkillDef s = skills.get(id);
        if (s == null) return;
        Result r = tryCast(p, s, null, left);
        if (r != Result.FAIL) {
            if (left) lastLeftCast.put(p.getUniqueId(), Bukkit.getCurrentTick());
            e.setCancelled(true);
        }
    }

    /** 좌클릭 스킬이 설정되어 있으면 바닐라 근접 공격 대신 스킬을 쓴다(마나 부족 등으로 실패하면 일반 공격). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLeftClickAttack(EntityDamageByEntityEvent e) {
        if (casting || pure || reflecting) return;
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != DamageCause.ENTITY_ATTACK) return;
        if (!(e.getEntity() instanceof LivingEntity t) || !validTarget(p, t)) return;
        if (!weaponReady(p)) return;
        PData d = data(p);
        if (d.cls == null || d.left == null) return;
        SkillDef s = skills.get(d.left);
        if (s == null) return;
        Integer last = lastLeftCast.get(p.getUniqueId());
        if (last != null && last == Bukkit.getCurrentTick()) {
            e.setCancelled(true);
            return;
        }
        Result r = tryCast(p, s, t, true);
        if (r != Result.FAIL) {
            lastLeftCast.put(p.getUniqueId(), Bukkit.getCurrentTick());
            e.setCancelled(true);
        }
    }

    /**
     * 무기(1번 칸)를 든 채로 숫자키 2~9를 누르면, 그 칸에 등록된 스킬이 우클릭 스킬로 장착된다.
     * (웅크린 채 누르면 좌클릭 스킬) 등록이 없는 칸은 평소처럼 아이템이 바뀐다.
     */
    @EventHandler(ignoreCancelled = true)
    public void onHotbar(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (!enabled() || e.getPreviousSlot() != 0) return;
        int ns = e.getNewSlot();
        if (ns <= 0 || ns >= 9) return;
        if (!isRpg(p.getInventory().getItem(0))) return;
        PData d = data(p);
        if (d.cls == null || !d.hotkeys) return;
        String id = d.hot[ns];
        if (id == null) return;
        SkillDef s = skills.get(id);
        if (s == null || d.levels.getOrDefault(id, 0) <= 0) return;
        e.setCancelled(true);
        if (p.isSneaking()) {
            d.left = id;
            msg(p, ChatColor.YELLOW + "좌클릭 스킬: " + ChatColor.GOLD + s.name);
        } else {
            d.right = id;
            msg(p, ChatColor.YELLOW + "우클릭 스킬: " + ChatColor.GOLD + s.name);
        }
        save(p);
    }

    /** 액션바에 붙일 현재 선택 스킬 표시. 직업이 없으면 빈 문자열. */
    String hudText(Player p) {
        PData d = data.get(p.getUniqueId());
        if (d == null || d.cls == null || !enabled()) return "";
        SkillDef l = d.left == null ? null : skills.get(d.left);
        SkillDef r = d.right == null ? null : skills.get(d.right);
        return ChatColor.YELLOW + "좌:" + (l == null ? "기본" : l.name) + " 우:" + (r == null ? "-" : r.name);
    }

    // ===================================================================
    //  스킬 트리 GUI  (스킬 칸 = 티어(세로) x 열(가로), 오른쪽 한 줄 = 탭/정보)
    // ===================================================================

    private String blockReason(Player p, PData d, SkillDef s) {
        int cur = d.levels.getOrDefault(s.id, 0);
        if (cur >= s.maxLevel) return "최대 레벨";
        if (freePoints(d) <= 0) return "남은 포인트 없음";
        if (s.reqLevel > 0 && d.maxLevel < s.reqLevel) return "필요 레벨 " + s.reqLevel + " (현재 " + d.maxLevel + ")";
        for (String pr : s.prereq) {
            String[] parts = pr.split(":");
            SkillDef ps = skills.get(parts[0].trim());
            int need = 1;
            if (parts.length > 1) {
                try {
                    need = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    need = 1;
                }
            }
            if (ps != null && d.levels.getOrDefault(ps.id, 0) < need) return "선행 스킬: " + ps.name + " " + need + "레벨";
        }
        return null;
    }

    private boolean learn(Player p, PData d, SkillDef s) {
        String reason = blockReason(p, d, s);
        if (reason != null) {
            msg(p, ChatColor.RED + s.name + ": " + reason);
            return false;
        }
        d.levels.merge(s.id, 1, Integer::sum);
        save(p);
        return true;
    }

    void openTree(Player p, int tab) {
        PData d = data(p);
        if (d.cls == null || !classes.containsKey(d.cls)) {
            openClassChooser(p);
            return;
        }
        ClassDef c = classes.get(d.cls);
        SkillHolder h = new SkillHolder("tree");
        h.tab = Math.max(0, Math.min(tab, Math.min(3, c.tabs.size()) - 1));
        h.inv = Bukkit.createInventory(h, GUI_SIZE, ChatColor.DARK_RED + "스킬 - " + c.name);
        render(p, h);
        p.openInventory(h.inv);
    }

    private ItemStack named(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private void render(Player p, SkillHolder h) {
        PData d = data(p);
        ClassDef c = classes.get(d.cls);
        Inventory inv = h.inv;
        inv.clear();
        h.slotSkill.clear();
        if (c == null) return;

        for (int i = 0; i < c.tabs.size() && i < 3; i++) {
            ItemStack it = named(TAB_ICONS[i % TAB_ICONS.length],
                    (i == h.tab ? ChatColor.GOLD + "▶ " : ChatColor.GRAY + "") + c.tabs.get(i), null);
            inv.setItem(i * 9 + 8, it);
        }

        List<String> info = new ArrayList<>();
        info.add(ChatColor.GRAY + "남은 포인트: " + ChatColor.GREEN + freePoints(d) + ChatColor.GRAY + " / 총 " + totalPoints(d));
        info.add(ChatColor.GRAY + "(포인트 = 시작 " + plugin.getConfig().getInt("skill-system.start-points", 1)
                + " + 레벨 x" + plugin.getConfig().getInt("skill-system.points-per-level", 1) + ")");
        info.add(ChatColor.GRAY + "세로 줄 = 티어 (필요 레벨 1/6/12/18/24/30)");
        info.add("");
        info.add(ChatColor.YELLOW + "좌클릭: " + ChatColor.WHITE + "포인트 투자");
        info.add(ChatColor.YELLOW + "우클릭: " + ChatColor.WHITE + "우클릭 스킬로 장착");
        info.add(ChatColor.YELLOW + "Shift+우클릭: " + ChatColor.WHITE + "좌클릭 스킬로 장착");
        info.add(ChatColor.YELLOW + "숫자키 2~9: " + ChatColor.WHITE + "그 칸에 스킬 등록");
        info.add(ChatColor.YELLOW + "Q: " + ChatColor.WHITE + "이 스킬의 등록 해제");
        inv.setItem(STAR_SLOT, named(Material.NETHER_STAR, ChatColor.GOLD + "스킬 포인트", info));

        SkillDef ls = d.left == null ? null : skills.get(d.left);
        SkillDef rs = d.right == null ? null : skills.get(d.right);
        List<String> cur = new ArrayList<>();
        cur.add(ChatColor.GRAY + "좌클릭: " + ChatColor.WHITE + (ls == null ? "기본 공격" : ls.name));
        cur.add(ChatColor.GRAY + "우클릭: " + ChatColor.WHITE + (rs == null ? "(없음)" : rs.name));
        for (int i = 1; i < 9; i++) {
            SkillDef hs = d.hot[i] == null ? null : skills.get(d.hot[i]);
            cur.add(ChatColor.GRAY + "숫자키 " + (i + 1) + ": " + ChatColor.WHITE + (hs == null ? "-" : hs.name));
        }
        cur.add("");
        cur.add(ChatColor.DARK_GRAY + "무기는 1번 칸에 들고 있어야 스킬이 나갑니다.");
        inv.setItem(BOOK_SLOT, named(Material.WRITABLE_BOOK, ChatColor.AQUA + "현재 장착", cur));

        for (SkillDef s : skills.values()) {
            if (!s.classId.equals(d.cls) || s.tab != h.tab) continue;
            int slot = s.row * 9 + s.col;
            if (slot < 0 || slot >= GUI_SIZE) continue;
            inv.setItem(slot, skillIcon(p, d, s));
            h.slotSkill.put(slot, s.id);
        }
    }

    private ItemStack skillIcon(Player p, PData d, SkillDef s) {
        int base = d.levels.getOrDefault(s.id, 0);
        int eff = level(p, d, s);
        String reason = base >= s.maxLevel ? null : blockReason(p, d, s);
        ChatColor color = base > 0 ? ChatColor.GREEN : (reason == null ? ChatColor.YELLOW : ChatColor.RED);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + kindLabel(s.kind) + (s.tier > 0 ? "  |  필요 레벨 " + s.reqLevel : ""));
        if (!s.desc.isEmpty()) lore.add(ChatColor.GRAY + s.desc);
        lore.add("");
        lore.add(ChatColor.WHITE + "레벨: " + base + " / " + s.maxLevel
                + (eff > base ? ChatColor.AQUA + "  (장비/버프 포함 " + eff + ")" : ""));
        lore.addAll(describe(p, s, Math.max(1, eff)));
        if (!s.prereq.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String pr : s.prereq) {
                String[] parts = pr.split(":");
                SkillDef ps = skills.get(parts[0].trim());
                if (sb.length() > 0) sb.append(", ");
                sb.append(ps == null ? parts[0] : ps.name);
            }
            lore.add(ChatColor.GRAY + "선행: " + sb);
        }
        if (reason != null && base < s.maxLevel) lore.add(ChatColor.RED + "배울 수 없음: " + reason);
        if (!s.kind.equals("passive") && !s.kind.equals("display")) {
            if (d.right != null && d.right.equals(s.id)) lore.add(ChatColor.GOLD + "★ 우클릭 스킬");
            if (d.left != null && d.left.equals(s.id)) lore.add(ChatColor.GOLD + "★ 좌클릭 스킬");
            for (int i = 1; i < 9; i++) {
                if (s.id.equals(d.hot[i])) lore.add(ChatColor.GOLD + "★ 숫자키 " + (i + 1));
            }
        }
        ItemStack it = named(s.icon, color + s.name, lore);
        if (base > 0) it.setAmount(Math.min(64, base));
        return it;
    }

    private String kindLabel(String kind) {
        return switch (kind) {
            case "melee" -> "[공격] 단일 대상";
            case "aoe" -> "[함성/범위] 주변";
            case "fear" -> "[함성] 공포";
            case "whirlwind" -> "[공격] 회전";
            case "leap" -> "[이동] 도약";
            case "leap_attack" -> "[이동/공격] 대상에게 도약";
            case "buff" -> "[강화] 지속 효과";
            case "passive" -> "[패시브] 항상 적용";
            case "curse" -> "[저주] 주변/대상 몹";
            case "aura" -> "[오라] 켜 두면 주변에 적용";
            case "display" -> "[표시만] 아직 효과 없음";
            default -> kind;
        };
    }

    private String statLabel(String stat) {
        return switch (stat) {
            case "damage-pct", "damage-bonus-pct" -> "피해";
            case "magic-pct" -> "마법 피해";
            case "stun-seconds" -> "기절 지속시간";
            case "duration", "stack-seconds" -> "지속시간(초)";
            default -> stat;
        };
    }

    private List<String> describe(Player p, SkillDef s, int lv) {
        List<String> out = new ArrayList<>();
        ConfigurationSection c = s.sec;
        if (c.contains("damage-pct")) {
            out.add(ChatColor.GRAY + "피해: 무기 공격력의 " + fmt(val(p, s, "damage-pct", lv, 0)) + "%"
                    + (c.contains("flat-damage") ? " + " + fmt(val(p, s, "flat-damage", lv, 0)) : "")
                    + (c.getInt("hits", 1) > 1 ? " x" + c.getInt("hits") : ""));
        }
        if (c.contains("magic-pct") || hasSynergyFor(s, "magic-pct")) {
            out.add(ChatColor.LIGHT_PURPLE + "마법(비전) 피해: 무기 공격력의 " + fmt(val(p, s, "magic-pct", lv, 0)) + "% (방어/저항 무시)");
        }
        if (c.contains("damage-min")) {
            double mn = val(p, s, "damage-min", lv, 0);
            double bonus = 1.0 + val(p, s, "damage-bonus-pct", lv, 0) / 100.0;
            double rg = scaled(c, "damage-range", lv, 10);
            out.add(ChatColor.GRAY + "피해: " + fmt(mn * bonus) + " ~ " + fmt((mn + rg) * bonus) + " (고정)");
        }
        if (c.contains("radius") || c.contains("radius-yd")) out.add(ChatColor.GRAY + "범위: " + fmt(radius(p, s, lv, 0)) + "칸");
        if (c.contains("range") || c.contains("range-yd")) {
            double r = c.contains("range-yd") ? val(p, s, "range-yd", lv, 0) * lengthScale() : val(p, s, "range", lv, 0);
            out.add(ChatColor.GRAY + "사거리: " + fmt(r) + "칸");
        }
        if (c.contains("duration")) out.add(ChatColor.GRAY + "지속: " + fmt(val(p, s, "duration", lv, 0)) + "초");
        if (c.contains("flee-seconds")) {
            out.add(ChatColor.GRAY + "도망: " + fmt(val(p, s, "flee-seconds", lv, 0)) + "초, "
                    + fmt(val(p, s, "flee-dist-yd", lv, 0) * lengthScale()) + "칸");
        }
        if (c.contains("stun-seconds")) out.add(ChatColor.GRAY + "기절: " + fmt(val(p, s, "stun-seconds", lv, 0)) + "초");
        if (c.contains("knockback")) out.add(ChatColor.GRAY + "밀어내기: " + fmt(val(p, s, "knockback", lv, 0)));
        if (c.contains("attack-speed-max")) {
            out.add(ChatColor.GRAY + "적중마다 공격 속도 최대 +" + fmt(scaled(c, "attack-speed-max", lv, 0)) + "% (" + fmt(val(p, s, "stack-seconds", lv, 6)) + "초 유지)");
        }
        if (c.getBoolean("requires-offhand", false)) out.add(ChatColor.RED + "왼손에도 무기를 들어야 함");
        if (c.getBoolean("dual-targets", false)) out.add(ChatColor.GRAY + "왼손 무기가 있으면 두 대상을 한 번씩 공격");
        for (String section : new String[]{"curse", "curse2"}) {
            ConfigurationSection cu = c.getConfigurationSection(section);
            if (cu != null) {
                out.add(ChatColor.GRAY + "저주(" + cu.getString("type", "") + "): " + fmt(scaled(cu, "amount", lv, 0))
                        + (cu.contains("duration") ? " / " + fmt(scaled(cu, "duration", lv, 0)) + "초" : ""));
            }
        }
        ConfigurationSection b = c.getConfigurationSection("bonuses");
        if (b != null) {
            double defScale = plugin.getConfig().getDouble("skill-system.defense-scale", 0.2);
            for (String name : b.getKeys(false)) {
                double v = scaled(b, name, lv, 0);
                if (name.equals("방어력 증가")) v *= defScale;
                out.add(ChatColor.AQUA + name + " +" + fmt(v));
            }
            if (!c.getString("weapon", "").isEmpty()) out.add(ChatColor.GRAY + "해당 무기를 들고 있을 때만 적용");
        }
        ConfigurationSection dsp = c.getConfigurationSection("display");
        if (dsp != null) {
            for (String name : dsp.getKeys(false)) {
                out.add(ChatColor.DARK_GRAY + name + " " + fmt(scaled(dsp, name, lv, 0)) + " (표시만)");
            }
        }
        if (c.contains("mana")) out.add(ChatColor.BLUE + "마나: " + fmt(val(p, s, "mana", lv, 0)));
        if (s.speed.equals("attack")) out.add(ChatColor.BLUE + "공격 속도의 영향을 받음");
        if (s.speed.equals("cast")) out.add(ChatColor.BLUE + "시전 속도의 영향을 받음");
        for (Syn sy : s.syn) {
            SkillDef src = skills.get(sy.skill);
            if (src == null) continue;
            out.add(ChatColor.DARK_AQUA + "시너지: " + src.name + " 레벨당 " + statLabel(sy.stat) + " +" + fmt(sy.per)
                    + (sy.pct || sy.stat.endsWith("-pct") ? "%" : ""));
        }
        return out;
    }

    private boolean hasSynergyFor(SkillDef s, String stat) {
        for (Syn sy : s.syn) if (sy.stat.equals(stat)) return true;
        return false;
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.005) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private void openClassChooser(Player p) {
        SkillHolder h = new SkillHolder("class");
        h.inv = Bukkit.createInventory(h, 9, ChatColor.DARK_RED + "직업 선택");
        int slot = 2;
        for (ClassDef c : classes.values()) {
            if (slot > 6) break;
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + c.desc);
            lore.add("");
            lore.add(ChatColor.YELLOW + "클릭하여 선택");
            h.inv.setItem(slot, named(c.icon, ChatColor.GOLD + c.name, lore));
            h.slotSkill.put(slot, c.id);
            slot++;
        }
        p.openInventory(h.inv);
    }

    private void chooseClass(Player p, String classId) {
        ClassDef c = classes.get(classId);
        if (c == null) return;
        PData d = data(p);
        if (d.cls != null && !d.cls.equals(classId)) {
            msg(p, ChatColor.RED + "이미 직업이 있습니다. /rpgskill reset 으로 초기화 후 다시 고르세요.");
            return;
        }
        d.cls = classId;
        d.maxLevel = Math.max(d.maxLevel, p.getLevel());
        save(p);
        p.sendMessage(ChatColor.GREEN + "직업 선택: " + c.name + ChatColor.GRAY + "  (/rpgskill 로 스킬 트리를 엽니다)");
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        Inventory top = e.getInventory();
        if (!(top.getHolder() instanceof SkillHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) return;
        int slot = e.getSlot();

        if (h.type.equals("class")) {
            String cid = h.slotSkill.get(slot);
            if (cid != null) {
                chooseClass(p, cid);
                p.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> openTree(p, 0));
            }
            return;
        }

        // 탭 버튼 (오른쪽 줄 8, 17, 26번 칸)
        ClassDef cd = classes.get(data(p).cls);
        if (cd != null && slot % 9 == 8 && slot / 9 < Math.min(3, cd.tabs.size())) {
            h.tab = slot / 9;
            render(p, h);
            return;
        }

        String id = h.slotSkill.get(slot);
        if (id == null) return;
        SkillDef s = skills.get(id);
        if (s == null) return;
        PData d = data(p);
        ClickType ct = e.getClick();
        boolean bindable = !s.kind.equals("passive") && !s.kind.equals("display");

        if (ct == ClickType.NUMBER_KEY) {
            int b = e.getHotbarButton();
            if (b <= 0 || b > 8) {
                msg(p, ChatColor.GRAY + "숫자키 1은 무기 칸입니다. 2~9를 사용하세요.");
                return;
            }
            if (!bindable) {
                msg(p, ChatColor.GRAY + "이 스킬은 등록할 수 없습니다.");
                return;
            }
            if (d.levels.getOrDefault(id, 0) <= 0) {
                msg(p, ChatColor.RED + "먼저 스킬을 배우세요.");
                return;
            }
            for (int i = 1; i < 9; i++) if (id.equals(d.hot[i])) d.hot[i] = null;
            d.hot[b] = id;
            save(p);
            render(p, h);
            return;
        }
        if (ct == ClickType.DROP || ct == ClickType.CONTROL_DROP) {
            for (int i = 1; i < 9; i++) if (id.equals(d.hot[i])) d.hot[i] = null;
            if (id.equals(d.left)) d.left = null;
            if (id.equals(d.right)) d.right = null;
            save(p);
            render(p, h);
            return;
        }
        if (ct == ClickType.LEFT || ct == ClickType.SHIFT_LEFT) {
            if (learn(p, d, s)) render(p, h);
            return;
        }
        if (ct == ClickType.RIGHT || ct == ClickType.SHIFT_RIGHT) {
            if (!bindable) {
                msg(p, ChatColor.GRAY + "이 스킬은 장착할 수 없습니다.");
                return;
            }
            if (d.levels.getOrDefault(id, 0) <= 0) {
                msg(p, ChatColor.RED + "먼저 스킬을 배우세요.");
                return;
            }
            if (ct == ClickType.SHIFT_RIGHT) d.left = id;
            else d.right = id;
            save(p);
            render(p, h);
        }
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof SkillHolder) e.setCancelled(true);
    }

    // ===================================================================
    //  /rpgskill
    // ===================================================================

    boolean handleCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!enabled()) {
            sender.sendMessage(ChatColor.RED + "스킬 시스템이 꺼져 있습니다. (config skill-system.enabled)");
            return true;
        }
        PData d = data(p);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "" -> {
                openTree(p, 0);
                return true;
            }
            case "class" -> {
                if (args.length < 2) {
                    p.sendMessage(ChatColor.YELLOW + "직업 목록:");
                    for (ClassDef c : classes.values()) p.sendMessage(ChatColor.GRAY + " - " + c.id + " (" + c.name + ")");
                    p.sendMessage(ChatColor.GRAY + "현재 직업: " + (d.cls == null ? "없음" : d.cls));
                    return true;
                }
                if (!classes.containsKey(args[1].toLowerCase(Locale.ROOT))) {
                    p.sendMessage(ChatColor.RED + "그런 직업이 없습니다.");
                    return true;
                }
                chooseClass(p, args[1].toLowerCase(Locale.ROOT));
                return true;
            }
            case "mode", "hotkeys" -> {
                d.hotkeys = !d.hotkeys;
                save(p);
                p.sendMessage(ChatColor.YELLOW + "숫자키 스킬 선택: " + (d.hotkeys ? ChatColor.GREEN + "켜짐" : ChatColor.RED + "꺼짐 (숫자키가 평소처럼 아이템을 바꿉니다)"));
                return true;
            }
            case "left", "right" -> {
                if (args.length < 2) {
                    p.sendMessage(ChatColor.RED + "/rpgskill " + sub + " <스킬id|none>");
                    return true;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                if (id.equals("none")) {
                    if (sub.equals("left")) d.left = null;
                    else d.right = null;
                    save(p);
                    p.sendMessage(ChatColor.GRAY + "해제했습니다.");
                    return true;
                }
                SkillDef s = skills.get(id);
                if (s == null || s.kind.equals("passive") || s.kind.equals("display") || d.levels.getOrDefault(id, 0) <= 0) {
                    p.sendMessage(ChatColor.RED + "배운 액티브 스킬만 장착할 수 있습니다. (/rpgskill info)");
                    return true;
                }
                if (sub.equals("left")) d.left = id;
                else d.right = id;
                save(p);
                p.sendMessage(ChatColor.GREEN + (sub.equals("left") ? "좌클릭" : "우클릭") + " 스킬: " + s.name);
                return true;
            }
            case "bind" -> {
                if (args.length < 3) {
                    p.sendMessage(ChatColor.RED + "/rpgskill bind <2~9> <스킬id|none>");
                    return true;
                }
                int n;
                try {
                    n = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    p.sendMessage(ChatColor.RED + "숫자는 2~9 입니다.");
                    return true;
                }
                if (n < 2 || n > 9) {
                    p.sendMessage(ChatColor.RED + "숫자는 2~9 입니다. (1번은 무기 칸)");
                    return true;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                if (id.equals("none")) {
                    d.hot[n - 1] = null;
                } else {
                    SkillDef s = skills.get(id);
                    if (s == null || s.kind.equals("passive") || s.kind.equals("display") || d.levels.getOrDefault(id, 0) <= 0) {
                        p.sendMessage(ChatColor.RED + "배운 액티브 스킬만 등록할 수 있습니다.");
                        return true;
                    }
                    d.hot[n - 1] = id;
                }
                save(p);
                p.sendMessage(ChatColor.GREEN + "숫자키 " + n + " 등록 완료");
                return true;
            }
            case "learn" -> {
                if (args.length < 2) {
                    p.sendMessage(ChatColor.RED + "/rpgskill learn <스킬id>");
                    return true;
                }
                SkillDef s = skills.get(args[1].toLowerCase(Locale.ROOT));
                if (s == null || !s.classId.equals(d.cls)) {
                    p.sendMessage(ChatColor.RED + "내 직업의 스킬 id가 아닙니다. (/rpgskill info)");
                    return true;
                }
                if (learn(p, d, s)) p.sendMessage(ChatColor.GREEN + s.name + " Lv" + d.levels.get(s.id));
                return true;
            }
            case "reset" -> {
                if (!plugin.getConfig().getBoolean("skill-system.allow-reset", true) && !p.hasPermission("diablorpg.admin")) {
                    p.sendMessage(ChatColor.RED + "초기화가 허용되지 않습니다.");
                    return true;
                }
                d.cls = null;
                d.levels.clear();
                d.left = null;
                d.right = null;
                d.activeAura = null;
                for (int i = 0; i < d.hot.length; i++) d.hot[i] = null;
                save(p);
                p.sendMessage(ChatColor.YELLOW + "스킬을 초기화했습니다. /rpgskill 로 직업을 다시 고르세요.");
                return true;
            }
            case "points" -> {
                if (args.length >= 3 && args[1].equalsIgnoreCase("give")) {
                    if (!p.hasPermission("diablorpg.admin")) {
                        p.sendMessage(ChatColor.RED + "권한이 없습니다.");
                        return true;
                    }
                    try {
                        d.bonusPoints += Integer.parseInt(args[2]);
                    } catch (NumberFormatException ex) {
                        p.sendMessage(ChatColor.RED + "숫자를 입력하세요.");
                        return true;
                    }
                    save(p);
                }
                p.sendMessage(ChatColor.YELLOW + "스킬 포인트: 남은 " + freePoints(d) + " / 총 " + totalPoints(d)
                        + ChatColor.GRAY + " (최대 도달 레벨 " + d.maxLevel + ", 관리자 지급 " + d.bonusPoints + ")");
                return true;
            }
            case "level" -> {
                // 관리자 테스트용: 스킬 필요 레벨(티어) 조건을 만족시키려고 "도달한 최대 레벨"을 올린다.
                if (!p.hasPermission("diablorpg.admin")) {
                    p.sendMessage(ChatColor.RED + "권한이 없습니다.");
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage(ChatColor.RED + "/rpgskill level <숫자>  (필요 레벨 테스트용, 포인트도 같이 늘어납니다)");
                    return true;
                }
                try {
                    d.maxLevel = Math.max(d.maxLevel, Integer.parseInt(args[1]));
                } catch (NumberFormatException ex) {
                    p.sendMessage(ChatColor.RED + "숫자를 입력하세요.");
                    return true;
                }
                save(p);
                p.sendMessage(ChatColor.YELLOW + "도달 레벨: " + d.maxLevel + "  남은 포인트: " + freePoints(d));
                return true;
            }
            case "cast" -> {
                if (!p.hasPermission("diablorpg.admin")) {
                    p.sendMessage(ChatColor.RED + "권한이 없습니다.");
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage(ChatColor.RED + "/rpgskill cast <스킬id>  (무기 칸 조건 없이 시험 시전)");
                    return true;
                }
                SkillDef s = skills.get(args[1].toLowerCase(Locale.ROOT));
                if (s == null) {
                    p.sendMessage(ChatColor.RED + "스킬 id를 찾을 수 없습니다.");
                    return true;
                }
                tryCast(p, s, null, false);
                return true;
            }
            case "info" -> {
                printInfo(p, d);
                return true;
            }
            default -> {
                p.sendMessage(ChatColor.YELLOW + "/rpgskill [class|mode|left|right|bind|learn|reset|points|level|cast|info]");
                return true;
            }
        }
    }

    private void printInfo(Player p, PData d) {
        p.sendMessage(ChatColor.GOLD + "=== 스킬 정보 ===");
        p.sendMessage(ChatColor.YELLOW + "직업: " + ChatColor.WHITE + (d.cls == null ? "없음" : d.cls)
                + ChatColor.YELLOW + "  포인트: " + ChatColor.WHITE + freePoints(d) + "/" + totalPoints(d)
                + ChatColor.YELLOW + "  도달 레벨: " + ChatColor.WHITE + d.maxLevel
                + ChatColor.YELLOW + "  숫자키: " + ChatColor.WHITE + (d.hotkeys ? "켜짐" : "꺼짐"));
        p.sendMessage(ChatColor.YELLOW + "좌: " + ChatColor.WHITE + nz(d.left) + ChatColor.YELLOW + "  우: " + ChatColor.WHITE + nz(d.right)
                + ChatColor.YELLOW + "  오라: " + ChatColor.WHITE + nz(d.activeAura));
        StringBuilder hot = new StringBuilder();
        for (int i = 1; i < 9; i++) if (d.hot[i] != null) hot.append(i + 1).append("=").append(d.hot[i]).append(" ");
        p.sendMessage(ChatColor.YELLOW + "숫자키: " + ChatColor.WHITE + hot);
        for (SkillDef s : skills.values()) {
            if (!s.classId.equals(d.cls)) continue;
            int base = d.levels.getOrDefault(s.id, 0);
            if (base <= 0) continue;
            p.sendMessage(ChatColor.GRAY + " " + s.id + " (" + s.name + ") " + ChatColor.WHITE + base
                    + (level(p, d, s) > base ? ChatColor.AQUA + " → " + level(p, d, s) : ""));
        }
        Gear g = gearOf(p);
        p.sendMessage(ChatColor.YELLOW + "장비: 모든스킬 +" + g.all + " 시전속도 +" + g.castSpeed + "% 직업 " + g.cls + " 스킬레벨 " + g.lv
                + " 오라 " + g.aura + " 발동 " + g.proc);
        p.sendMessage(ChatColor.YELLOW + "스킬 보너스 합계: " + ChatColor.WHITE + cached(p).totals);
        AttributeInstance as = p.getAttribute(VersionCompat.ATTACK_SPEED);
        AttributeInstance ms = p.getAttribute(VersionCompat.MOVEMENT_SPEED);
        p.sendMessage(ChatColor.YELLOW + "공격속도 속성: " + ChatColor.WHITE + (as == null ? "-" : fmt(as.getValue()) + " (기본 " + fmt(as.getBaseValue()) + ")")
                + ChatColor.YELLOW + "  이동속도 속성: " + ChatColor.WHITE + (ms == null ? "-" : String.format(Locale.ROOT, "%.3f", ms.getValue()) + " (기본 " + String.format(Locale.ROOT, "%.3f", ms.getBaseValue()) + ")"));
        Frenzy fz = frenzy.get(p.getUniqueId());
        p.sendMessage(ChatColor.YELLOW + "광분 중첩: " + ChatColor.WHITE + (fz == null || fz.until < System.currentTimeMillis()
                ? "없음(만료)" : fz.stacks + "중첩, 남은 " + ((fz.until - System.currentTimeMillis()) / 1000) + "초"));
        LivingEntity look = findTarget(p, 20);
        if (look != null) {
            Map<String, Curse> m = curses.get(look.getUniqueId());
            p.sendMessage(ChatColor.YELLOW + "바라보는 대상 저주: " + ChatColor.WHITE + (m == null ? "없음" : describeCurses(m)));
        }
    }

    private String describeCurses(Map<String, Curse> m) {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Curse> e : m.entrySet()) {
            if (e.getValue().until < now) continue;
            sb.append(e.getKey()).append("=").append(fmt(e.getValue().amount))
                    .append("(").append((e.getValue().until - now) / 1000).append("s) ");
        }
        return sb.length() == 0 ? "없음" : sb.toString();
    }
}
