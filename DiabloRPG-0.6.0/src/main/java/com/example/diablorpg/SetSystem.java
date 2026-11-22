package com.example.diablorpg;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 세트 아이템 / 세트 보너스 (0.5.0, 전부 미검증).
 *
 * - config.yml 의 sets 섹션에서 세트와 부위를 읽는다. 코드를 고치지 않고 세트를 추가할 수 있다.
 * - 세트 부위는 희귀도 "set" 이고 PDC 에 세트 ID / 부위 ID 가 저장된다.
 * - 착용 중인 서로 다른 부위 수를 세어, 그 수 이하의 보너스 단계를 모두 합산한다 (누적).
 * - 같은 부위를 두 개 입어도 한 개로 센다.
 * - 보너스는 두 경로로 적용된다.
 *     (1) 전투 옵션(공격력 증가, 방어력 증가, 저항 등): 피해 계산 때마다 착용 상태를 읽어 합산한다.
 *     (2) 능력치 옵션(최대 생명력, 공격 속도, 이동 속도): 0.5초마다 플레이어에게 AttributeModifier 로 붙였다 뗀다.
 * - 세트 보너스로 쓸 수 없는 옵션: 원소 피해 전환(대상 원소 지정 방식 없음), 범위 피해.
 */
final class SetSystem {

    /** 세트 보너스 합산 결과. */
    static final class Bonus {
        /** 옵션 이름 -> 합산 수치 (모든 단계 누적). */
        final Map<String, Double> totals = new LinkedHashMap<>();
        /** totals 를 PDC 키로 옮겨 둔 임시 컨테이너. 전투 계산에서 아이템 PDC 와 같은 방식으로 읽는다. */
        final PersistentDataContainer pdc;

        Bonus(PersistentDataContainer pdc) {
            this.pdc = pdc;
        }
    }

    private final DiabloRPG plugin;
    private final NamespacedKey setIdKey;
    private final NamespacedKey setPieceKey;
    private final Map<UUID, String> lastState = new HashMap<>();

    SetSystem(DiabloRPG plugin) {
        this.plugin = plugin;
        this.setIdKey = new NamespacedKey(plugin, "set_id");
        this.setPieceKey = new NamespacedKey(plugin, "set_piece");
    }

    /** 능력치 보너스를 주기적으로 갱신하는 작업을 시작한다. */
    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    private ConfigurationSection setsSection() {
        return plugin.getConfig().getConfigurationSection("sets");
    }

    // ===================================================================
    //  아이템 생성
    // ===================================================================

    /** "세트ID" 또는 "세트ID:부위ID" 에 해당하는 아이템 목록. 없으면 빈 목록. */
    List<ItemStack> createFromRef(String ref) {
        List<ItemStack> result = new ArrayList<>();
        ConfigurationSection sets = setsSection();
        if (sets == null) return result;
        String[] parts = ref.toLowerCase(Locale.ROOT).split(":", 2);
        ConfigurationSection set = sets.getConfigurationSection(parts[0]);
        if (set == null) return result;
        ConfigurationSection pieces = set.getConfigurationSection("pieces");
        if (pieces == null) return result;
        if (parts.length == 2) {
            ItemStack one = createPiece(parts[0], parts[1]);
            if (one != null) result.add(one);
        } else {
            for (String pieceId : pieces.getKeys(false)) {
                ItemStack one = createPiece(parts[0], pieceId);
                if (one != null) result.add(one);
            }
        }
        return result;
    }

    String setIds() {
        ConfigurationSection sets = setsSection();
        if (sets == null || sets.getKeys(false).isEmpty()) return "(없음)";
        List<String> out = new ArrayList<>();
        for (String id : sets.getKeys(false)) {
            ConfigurationSection pieces = sets.getConfigurationSection(id + ".pieces");
            out.add(id + (pieces == null ? "" : pieces.getKeys(false)));
        }
        return String.join(", ", out);
    }

    private ItemStack createPiece(String setId, String pieceId) {
        ConfigurationSection set = setsSection() == null ? null : setsSection().getConfigurationSection(setId);
        if (set == null) return null;
        ConfigurationSection piece = set.getConfigurationSection("pieces." + pieceId);
        if (piece == null) return null;
        Material material = Material.matchMaterial(piece.getString("base", "IRON_SWORD"));
        if (material == null || !material.isItem()) return null;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(plugin.rarityKey, PersistentDataType.STRING, "set");
        pdc.set(setIdKey, PersistentDataType.STRING, setId);
        pdc.set(setPieceKey, PersistentDataType.STRING, pieceId);
        meta.setDisplayName(plugin.rarityColor("set") + piece.getString("name", pieceId));

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "등급: " + plugin.getConfig().getString("rarities.set.display", "세트"));
        lore.add(ChatColor.DARK_GRAY + plugin.prettyName(material));

        ConfigurationSection options = piece.getConfigurationSection("options");
        if (options != null) {
            for (String optName : options.getKeys(false)) {
                ConfigurationSection opt = options.getConfigurationSection(optName);
                if (opt == null) continue;
                int lo = opt.getInt("min", 0);
                int value = plugin.rollInt(lo, opt.getInt("max", lo));
                lore.add(ChatColor.GREEN + optName + " +" + value + opt.getString("suffix", ""));
                plugin.applyOptionToPdc(meta, optName, value);
            }
        }
        for (String line : piece.getStringList("inactive")) {
            lore.add(ChatColor.DARK_GRAY + line + " (효과 없음)");
        }

        // 세트 설명: 세트 이름, 부위 목록, 단계별 보너스 (정적 표시. 현재 착용 상태는 /rpgset)
        lore.add("");
        lore.add(ChatColor.GOLD + set.getString("name", setId));
        ConfigurationSection pieces = set.getConfigurationSection("pieces");
        if (pieces != null) {
            for (String id : pieces.getKeys(false)) {
                lore.add(ChatColor.GREEN + "  " + pieces.getString(id + ".name", id));
            }
        }
        ConfigurationSection bonuses = set.getConfigurationSection("bonuses");
        if (bonuses != null) {
            for (String tier : bonuses.getKeys(false)) {
                lore.add(ChatColor.GOLD + tier + "세트 보너스");
                lore.addAll(describeBonusTier(bonuses.getConfigurationSection(tier), ChatColor.GRAY));
            }
        }

        plugin.applyAttributeModifiers(meta, material);

        lore.add(ChatColor.DARK_GRAY + "DiabloRPG " + DiabloRPG.VERSION);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private List<String> describeBonusTier(ConfigurationSection tier, ChatColor color) {
        List<String> out = new ArrayList<>();
        if (tier == null) return out;
        ConfigurationSection options = tier.getConfigurationSection("options");
        if (options != null) {
            for (String name : options.getKeys(false)) {
                ConfigurationSection opt = options.getConfigurationSection(name);
                if (opt == null) continue;
                out.add(color + "  " + name + " +" + opt.getInt("value", 0) + opt.getString("suffix", ""));
            }
        }
        for (String line : tier.getStringList("inactive")) {
            out.add(ChatColor.DARK_GRAY + "  " + line + " (효과 없음)");
        }
        return out;
    }

    // ===================================================================
    //  착용 상태 읽기 / 보너스 합산
    // ===================================================================

    /** 플레이어가 착용 중인 세트 부위: 세트ID -> 서로 다른 부위ID 집합. */
    Map<String, Set<String>> wornPieces(Player player) {
        Map<String, Set<String>> worn = new LinkedHashMap<>();
        PlayerInventory inv = player.getInventory();
        // 방어구 4칸
        for (ItemStack armor : inv.getArmorContents()) addPiece(worn, armor);
        // 보조 손: 방패만 센다
        ItemStack off = inv.getItemInOffHand();
        if (off != null && off.getType() == Material.SHIELD) addPiece(worn, off);
        // 주 손: 방어구/방패가 아닌 것(무기)만 센다
        ItemStack main = inv.getItemInMainHand();
        if (main != null && !isArmorOrShield(main.getType())) addPiece(worn, main);
        return worn;
    }

    private boolean isArmorOrShield(Material m) {
        String n = m.name();
        return m == Material.SHIELD || m == Material.ELYTRA
                || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }

    private void addPiece(Map<String, Set<String>> worn, ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String setId = pdc.get(setIdKey, PersistentDataType.STRING);
        String pieceId = pdc.get(setPieceKey, PersistentDataType.STRING);
        if (setId == null || pieceId == null) return;
        worn.computeIfAbsent(setId, k -> new LinkedHashSet<>()).add(pieceId);
    }

    /** 현재 착용 상태로 활성화된 세트 보너스 합산. 활성 보너스가 없으면 null. */
    Bonus bonusFor(Player player) {
        ConfigurationSection sets = setsSection();
        if (sets == null) return null;
        Map<String, Set<String>> worn = wornPieces(player);
        if (worn.isEmpty()) return null;

        Map<String, Double> totals = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : worn.entrySet()) {
            ConfigurationSection bonuses = sets.getConfigurationSection(e.getKey() + ".bonuses");
            if (bonuses == null) continue;
            int count = e.getValue().size();
            for (String tierKey : bonuses.getKeys(false)) {
                int need;
                try {
                    need = Integer.parseInt(tierKey);
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (need > count) continue;
                ConfigurationSection options = bonuses.getConfigurationSection(tierKey + ".options");
                if (options == null) continue;
                for (String name : options.getKeys(false)) {
                    if (name.equals("원소 피해 전환")) continue; // 세트 보너스로는 지원하지 않음
                    totals.merge(name, options.getDouble(name + ".value", 0.0), Double::sum);
                }
            }
        }
        if (totals.isEmpty()) return null;

        ItemMeta scratch = new ItemStack(Material.STONE).getItemMeta();
        for (Map.Entry<String, Double> t : totals.entrySet()) {
            plugin.applyOptionToPdc(scratch, t.getKey(), (int) Math.round(t.getValue()));
        }
        Bonus bonus = new Bonus(scratch.getPersistentDataContainer());
        bonus.totals.putAll(totals);
        return bonus;
    }

    // ===================================================================
    //  능력치 보너스 (최대 생명력 / 공격 속도 / 이동 속도) 주기 갱신
    // ===================================================================

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Bonus b = bonusFor(p);
            double hp = b == null ? 0 : b.totals.getOrDefault("최대 생명력", 0.0);
            double atk = b == null ? 0 : b.totals.getOrDefault("공격 속도 증가", 0.0);
            double mov = b == null ? 0 : b.totals.getOrDefault("이동 속도", 0.0);
            setModifier(p, VersionCompat.MAX_HEALTH, "setbonus_maxhealth", hp,
                    AttributeModifier.Operation.ADD_NUMBER);
            setModifier(p, VersionCompat.ATTACK_SPEED, "setbonus_atkspeed", 4.0 * atk / 100.0,
                    AttributeModifier.Operation.ADD_NUMBER);
            setModifier(p, VersionCompat.MOVEMENT_SPEED, "setbonus_movespeed", mov / 100.0,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1);
            logIfChanged(p);
        }
        lastState.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
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

    /** 착용 부위 수가 바뀌면 콘솔에 한 줄 남긴다 (테스트용). */
    private void logIfChanged(Player p) {
        Map<String, Set<String>> worn = wornPieces(p);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Set<String>> e : worn.entrySet()) {
            int total = plugin.getConfig().getConfigurationSection("sets." + e.getKey() + ".pieces") == null
                    ? 0 : plugin.getConfig().getConfigurationSection("sets." + e.getKey() + ".pieces")
                    .getKeys(false).size();
            sb.append(e.getKey()).append(' ').append(e.getValue().size()).append('/').append(total).append("  ");
        }
        Bonus b = bonusFor(p);
        if (b != null) sb.append("| 활성 보너스 합계: ").append(b.totals);
        String state = sb.toString().trim();
        String prev = lastState.put(p.getUniqueId(), state);
        if (prev == null) prev = "";
        if (!prev.equals(state)) {
            plugin.getLogger().info("[세트] " + p.getName() + " -> " + (state.isEmpty() ? "(세트 착용 없음)" : state));
        }
    }

    // ===================================================================
    //  명령어 /rpgset  (착용 현황),  /rpgset list (세트 목록)
    // ===================================================================

    boolean handleCommand(CommandSender sender, String[] args) {
        ConfigurationSection sets = setsSection();
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(ChatColor.YELLOW + "등록된 세트: " + setIds());
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.YELLOW + "사용법: /rpgset list  (플레이어는 /rpgset 으로 착용 현황 확인)");
            return true;
        }
        Map<String, Set<String>> worn = wornPieces(p);
        if (worn.isEmpty() || sets == null) {
            p.sendMessage(ChatColor.GRAY + "착용 중인 세트 아이템이 없습니다.");
            return true;
        }
        for (Map.Entry<String, Set<String>> e : worn.entrySet()) {
            ConfigurationSection set = sets.getConfigurationSection(e.getKey());
            if (set == null) continue;
            ConfigurationSection pieces = set.getConfigurationSection("pieces");
            int total = pieces == null ? 0 : pieces.getKeys(false).size();
            int count = e.getValue().size();
            p.sendMessage(ChatColor.GOLD + set.getString("name", e.getKey())
                    + ChatColor.WHITE + "  " + count + "/" + total);
            ConfigurationSection bonuses = set.getConfigurationSection("bonuses");
            if (bonuses == null) continue;
            for (String tierKey : bonuses.getKeys(false)) {
                int need;
                try {
                    need = Integer.parseInt(tierKey);
                } catch (NumberFormatException ex) {
                    continue;
                }
                boolean active = need <= count;
                ChatColor c = active ? ChatColor.GREEN : ChatColor.DARK_GRAY;
                p.sendMessage(c + (active ? "[활성] " : "[비활성] ") + need + "세트 보너스");
                for (String line : describeBonusTier(bonuses.getConfigurationSection(tierKey), c)) {
                    p.sendMessage(line);
                }
            }
        }
        return true;
    }
}
