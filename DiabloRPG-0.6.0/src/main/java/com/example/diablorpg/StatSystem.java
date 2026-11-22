package com.example.diablorpg;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 능력치(힘/민첩/활력/마력)와 마나, 생명력 회복 (0.6.0, 전부 미검증).
 *
 * 능력치는 장비 옵션으로만 올라간다 (레벨업으로 분배하는 시스템 없음). 합산 대상은
 * 방어구 4칸 + 보조 손 방패 + 주 손 무기(방어구/방패 제외) + 활성 세트 보너스.
 *
 *  - 힘:   근접 공격의 물리 피해 +N% (공격력 증가 %에 더해진다). 활/석궁에는 적용하지 않는다.
 *  - 민첩: 활/석궁 공격의 물리 피해 +N% 와 방어력 증가 +N%.
 *  - 활력: 최대 생명력 (플레이어 AttributeModifier, 0.5초마다 갱신).
 *  - 마력: 최대 마나.
 *  - 마나 증가: 최대 마나 고정 수치. 마나 회복: 초당 마나 회복. 생명력 회복: 초당 생명력 회복.
 *
 * 마나는 아직 소비처(스킬)가 없다. 최대치, 회복, 표시, 저장만 동작한다.
 * 모든 수치는 config.yml 의 stats / mana / life-regen 섹션에서 조정한다.
 */
final class StatSystem {

    /** 장비에서 합산한 능력치. */
    static final class Stats {
        double strength;
        double dexterity;
        double vitality;
        double energy;
        double manaFlat;     // 마나 증가 (최대 마나 고정 수치)
        double manaRegen;    // 마나 회복 (옵션 합, 초당)
        double lifeRegen;    // 생명력 회복 (옵션 합)
    }

    private static final double TICK_SECONDS = 0.5; // tick() 주기 (10틱)

    private final DiabloRPG plugin;
    private final NamespacedKey manaKey;

    StatSystem(DiabloRPG plugin) {
        this.plugin = plugin;
        this.manaKey = new NamespacedKey(plugin, "mana_current");
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    // ===================================================================
    //  합산
    // ===================================================================

    Stats collect(Player player) {
        Stats s = new Stats();
        PlayerInventory inv = player.getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack a : inv.getArmorContents()) items.add(a);
        ItemStack off = inv.getItemInOffHand();
        if (off != null && off.getType() == Material.SHIELD) items.add(off);
        ItemStack main = inv.getItemInMainHand();
        if (main != null && !isArmorOrShield(main.getType())) items.add(main);

        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || !item.hasItemMeta()) continue;
            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            if (!pdc.has(plugin.rarityKey, PersistentDataType.STRING)) continue;
            add(s, pdc);
        }
        SetSystem.Bonus bonus = plugin.setSystem().bonusFor(player);
        if (bonus != null) add(s, bonus.pdc);
        return s;
    }

    private void add(Stats s, PersistentDataContainer pdc) {
        s.strength += pdc.getOrDefault(plugin.strengthKey, PersistentDataType.DOUBLE, 0.0);
        s.dexterity += pdc.getOrDefault(plugin.dexterityKey, PersistentDataType.DOUBLE, 0.0);
        s.vitality += pdc.getOrDefault(plugin.vitalityKey, PersistentDataType.DOUBLE, 0.0);
        s.energy += pdc.getOrDefault(plugin.energyKey, PersistentDataType.DOUBLE, 0.0);
        s.manaFlat += pdc.getOrDefault(plugin.maxManaKey, PersistentDataType.DOUBLE, 0.0);
        s.manaRegen += pdc.getOrDefault(plugin.manaRegenKey, PersistentDataType.DOUBLE, 0.0);
        s.lifeRegen += pdc.getOrDefault(plugin.lifeRegenKey, PersistentDataType.DOUBLE, 0.0);
    }

    private boolean isArmorOrShield(Material m) {
        String n = m.name();
        return m == Material.SHIELD || m == Material.ELYTRA
                || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }

    // ===================================================================
    //  능력치 -> 전투 수치 (config 로 조정)
    // ===================================================================

    /** 공격력 증가 %에 더할 값. 근접은 힘, 활/석궁은 민첩. */
    double attackPctBonus(Stats s, boolean ranged) {
        if (ranged) {
            return s.dexterity * plugin.getConfig().getDouble("stats.dexterity.ranged-phys-pct-per-point", 1.0);
        }
        return s.strength * plugin.getConfig().getDouble("stats.strength.melee-phys-pct-per-point", 1.0);
    }

    /** 방어력 증가 %에 더할 값 (민첩). */
    double defensePctBonus(Stats s) {
        return s.dexterity * plugin.getConfig().getDouble("stats.dexterity.defense-pct-per-point", 0.5);
    }

    double healthBonus(Stats s) {
        return s.vitality * plugin.getConfig().getDouble("stats.vitality.health-per-point", 2.0);
    }

    double maxMana(Stats s) {
        double base = plugin.getConfig().getDouble("mana.base", 20.0);
        double perEnergy = plugin.getConfig().getDouble("stats.energy.mana-per-point", 2.0);
        return Math.max(0.0, base + s.energy * perEnergy + s.manaFlat);
    }

    double manaRegenPerSec(Stats s) {
        return Math.max(0.0, plugin.getConfig().getDouble("mana.base-regen-per-sec", 0.5) + s.manaRegen);
    }

    double lifeRegenPerSec(Stats s) {
        return Math.max(0.0, s.lifeRegen * plugin.getConfig().getDouble("life-regen.per-point-per-sec", 0.2));
    }

    // ===================================================================
    //  마나 저장 (플레이어 PDC: 로그아웃/서버 재시작 후에도 유지)
    // ===================================================================

    double getMana(Player p) {
        Double v = p.getPersistentDataContainer().get(manaKey, PersistentDataType.DOUBLE);
        return v == null ? -1.0 : v; // -1 = 아직 초기화되지 않음 (최대치로 시작)
    }

    void setMana(Player p, double value) {
        p.getPersistentDataContainer().set(manaKey, PersistentDataType.DOUBLE, Math.max(0.0, value));
    }

    // ===================================================================
    //  0.5초마다: 활력 -> 최대 생명력, 마나/생명력 회복, 액션바
    // ===================================================================

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) continue;
            Stats s = collect(p);

            setModifier(p, VersionCompat.MAX_HEALTH, "stat_vitality_health", healthBonus(s),
                    AttributeModifier.Operation.ADD_NUMBER);

            // 생명력 회복
            AttributeInstance hpAttr = p.getAttribute(VersionCompat.MAX_HEALTH);
            double maxHp = hpAttr == null ? 20.0 : hpAttr.getValue();
            double lifeRegen = lifeRegenPerSec(s);
            if (lifeRegen > 0 && p.getHealth() < maxHp) {
                p.setHealth(Math.min(maxHp, p.getHealth() + lifeRegen * TICK_SECONDS));
            }

            // 마나
            double maxMana = maxMana(s);
            double mana = getMana(p);
            if (mana < 0) mana = maxMana; // 처음에는 가득 찬 상태
            mana = Math.min(maxMana, mana + manaRegenPerSec(s) * TICK_SECONDS);
            setMana(p, mana);

            if (plugin.getConfig().getBoolean("mana.actionbar", true) && maxMana > 0) {
                String text = ChatColor.RED + "생명력 " + (int) Math.ceil(p.getHealth()) + "/" + (int) Math.round(maxHp)
                        + ChatColor.GRAY + "   " + ChatColor.AQUA + "마나 " + (int) Math.floor(mana) + "/"
                        + (int) Math.round(maxMana);
                p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(text));
            }
        }
    }

    private void setModifier(Player p, Attribute attribute, String keyName, double amount,
                             AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attribute);
        if (inst == null) return;
        NamespacedKey key = new NamespacedKey(plugin, keyName);
        AttributeModifier existing = null;
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (key.equals(m.getKey())) {
                existing = m;
                break;
            }
        }
        if (existing != null) {
            if (existing.getAmount() == amount && existing.getOperation() == op) return;
            inst.removeModifier(existing);
        }
        if (amount != 0.0) {
            inst.addModifier(new AttributeModifier(key, amount, op));
        }
    }

    // ===================================================================
    //  /rpgstat            내 능력치와 파생 수치 확인
    //  /rpgstat setmana N  (관리자) 현재 마나를 N으로 설정 (회복 테스트용)
    // ===================================================================

    boolean handleCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.YELLOW + "플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("setmana")) {
            if (!sender.hasPermission("diablorpg.admin")) {
                sender.sendMessage(ChatColor.RED + "권한이 없습니다.");
                return true;
            }
            if (args.length != 2) {
                sender.sendMessage(ChatColor.YELLOW + "사용법: /rpgstat setmana <수치>");
                return true;
            }
            try {
                setMana(p, Double.parseDouble(args[1]));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "숫자를 입력하세요.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "현재 마나를 " + args[1] + "(으)로 설정했습니다.");
            return true;
        }

        Stats s = collect(p);
        AttributeInstance hpAttr = p.getAttribute(VersionCompat.MAX_HEALTH);
        double mana = getMana(p);
        double maxMana = maxMana(s);
        if (mana < 0) mana = maxMana;
        p.sendMessage(ChatColor.GOLD + "=== 능력치 ===");
        p.sendMessage(String.format(Locale.ROOT, "%s힘 %.0f%s  (근접 물리 피해 +%.1f%%)",
                ChatColor.WHITE, s.strength, ChatColor.GRAY, attackPctBonus(s, false)));
        p.sendMessage(String.format(Locale.ROOT, "%s민첩 %.0f%s  (활/석궁 물리 피해 +%.1f%%, 방어력 증가 +%.1f%%)",
                ChatColor.WHITE, s.dexterity, ChatColor.GRAY, attackPctBonus(s, true), defensePctBonus(s)));
        p.sendMessage(String.format(Locale.ROOT, "%s활력 %.0f%s  (최대 생명력 +%.1f)",
                ChatColor.WHITE, s.vitality, ChatColor.GRAY, healthBonus(s)));
        p.sendMessage(String.format(Locale.ROOT, "%s마력 %.0f%s  (최대 마나 +%.1f)",
                ChatColor.WHITE, s.energy, ChatColor.GRAY,
                s.energy * plugin.getConfig().getDouble("stats.energy.mana-per-point", 2.0)));
        p.sendMessage(String.format(Locale.ROOT, "%s마나 %.1f / %.1f  (초당 회복 %.2f)",
                ChatColor.AQUA, mana, maxMana, manaRegenPerSec(s)));
        p.sendMessage(String.format(Locale.ROOT, "%s생명력 %.1f / %.1f  (옵션 회복 초당 %.2f)",
                ChatColor.RED, p.getHealth(), hpAttr == null ? 20.0 : hpAttr.getValue(), lifeRegenPerSec(s)));
        return true;
    }
}
