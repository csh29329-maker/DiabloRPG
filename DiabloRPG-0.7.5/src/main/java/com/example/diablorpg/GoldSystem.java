package com.example.diablorpg;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 골드 보관 / 몬스터 골드·장비 드롭 / 상점 / 도박 시스템 (0.6.2).
 *
 *  - 골드는 플레이어 PDC(gold_amount)에 정수로 저장된다.
 *  - 상점·도박은 GOLD_BLOCK 우클릭, 소환된 상인 NPC 우클릭, 또는 /rpgshop · /rpggamble 로 연다.
 *  - 골드 획득량 옵션은 몬스터 처치 골드에 적용되고,
 *    아이템 발견 확률 옵션은 몬스터 RPG 장비 드롭(및 설정 시 도박/룬 드롭)에 연결된다.
 *  - 도박 등급 범위: 매직 / 레어 / 낮은 확률로 세트·유니크 (config.yml 에서 확률 조정).
 */
final class GoldSystem implements Listener {

    private static final int INV_SIZE = 27;
    private static final int GOLD_DISPLAY_SLOT = 22;
    private static final int SWITCH_TAB_SLOT = 26;

    private static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;
        @Override
        public Inventory getInventory() { return inventory; }
    }

    private static final class GambleHolder implements InventoryHolder {
        private Inventory inventory;
        @Override
        public Inventory getInventory() { return inventory; }
    }

    private record ShopOffer(int slot, Material material, String rarity, String title, long price) {}
    private record GambleOffer(int slot, String category, Material icon, String title, long basePrice) {}

    private static final ShopOffer[] SHOP_OFFERS = {
            new ShopOffer(10, Material.IRON_SWORD, "normal", "노말 철 검", 40L),
            new ShopOffer(11, Material.DIAMOND_SWORD, "normal", "노말 다이아몬드 검", 120L),
            new ShopOffer(12, Material.DIAMOND_AXE, "normal", "노말 다이아몬드 도끼", 120L),
            new ShopOffer(13, Material.BOW, "normal", "노말 활", 90L),
            new ShopOffer(14, Material.CROSSBOW, "normal", "노말 석궁", 110L),
            new ShopOffer(15, Material.DIAMOND_HELMET, "normal", "노말 다이아몬드 투구", 100L),
            new ShopOffer(16, Material.DIAMOND_CHESTPLATE, "normal", "노말 다이아몬드 갑옷", 150L),
            new ShopOffer(19, Material.SHIELD, "normal", "노말 방패", 80L),
            new ShopOffer(20, Material.DIAMOND_SWORD, "magic", "매직 다이아몬드 검", 220L),
            new ShopOffer(21, Material.DIAMOND_CHESTPLATE, "magic", "매직 다이아몬드 갑옷", 260L),
    };

    private static final GambleOffer[] GAMBLE_OFFERS = {
            new GambleOffer(10, "sword", Material.IRON_SWORD, "미확인 검 도박", 120L),
            new GambleOffer(11, "axe", Material.IRON_AXE, "미확인 도끼 도박", 120L),
            new GambleOffer(12, "bow", Material.BOW, "미확인 활 도박", 110L),
            new GambleOffer(13, "crossbow", Material.CROSSBOW, "미확인 석궁 도박", 130L),
            new GambleOffer(14, "helmet", Material.IRON_HELMET, "미확인 투구 도박", 100L),
            new GambleOffer(15, "armor", Material.IRON_CHESTPLATE, "미확인 갑옷 도박", 150L),
            new GambleOffer(16, "boots", Material.IRON_BOOTS, "미확인 장화 도박", 95L),
            new GambleOffer(20, "shield", Material.SHIELD, "미확인 방패 도박", 110L),
    };

    private final DiabloRPG plugin;
    private final NamespacedKey goldKey;
    private final NamespacedKey merchantKey;
    private final Random random = new Random();

    GoldSystem(DiabloRPG plugin) {
        this.plugin = plugin;
        this.goldKey = new NamespacedKey(plugin, "gold_amount");
        this.merchantKey = new NamespacedKey(plugin, "merchant_npc");
    }

    // ===================================================================
    //  골드 보관 (플레이어 PDC)
    // ===================================================================

    long getGold(Player player) {
        Long val = player.getPersistentDataContainer().get(goldKey, PersistentDataType.LONG);
        return val == null ? 0L : Math.max(0L, val);
    }

    void setGold(Player player, long amount) {
        player.getPersistentDataContainer().set(goldKey, PersistentDataType.LONG, Math.max(0L, amount));
    }

    void addGold(Player player, long delta) {
        if (delta <= 0) return;
        long next = Math.max(0L, getGold(player) + delta);
        setGold(player, next);
    }

    boolean spendGold(Player player, long cost) {
        if (cost <= 0) return true;
        long cur = getGold(player);
        if (cur < cost) return false;
        setGold(player, cur - cost);
        return true;
    }

    // ===================================================================
    //  상점 할인 (룬워드 '모서리' 보유 시 15% 할인)
    // ===================================================================

    private double discountFactor(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null && !main.getType().isAir() && main.hasItemMeta()) {
            String rw = main.getItemMeta().getPersistentDataContainer()
                    .get(plugin.runeSystem().runewordKey, PersistentDataType.STRING);
            if ("edge".equalsIgnoreCase(rw)) {
                return 0.85;
            }
        }
        return 1.0;
    }

    private long effectivePrice(Player player, long basePrice) {
        return Math.max(1L, Math.round(basePrice * discountFactor(player)));
    }

    // ===================================================================
    //  몬스터 처치: 골드 적립 + RPG 장비 드롭 (골드 획득량 / 아이템 발견 확률)
    // ===================================================================

    private boolean isBoss(EntityType type) {
        return switch (type) {
            case WITHER, ENDER_DRAGON, WARDEN, ELDER_GUARDIAN -> true;
            default -> false;
        };
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player) return;
        Player killer = dead.getKiller();
        if (killer == null || !killer.isOnline()) return;
        if (plugin.isDummy(dead)) return;

        boolean boss = isBoss(dead.getType());
        if (!boss && !(dead instanceof Enemy)) return;

        StatSystem.Stats stats = plugin.statSystem().collect(killer);

        // 1) 골드 적립
        if (plugin.getConfig().getBoolean("gold.drop.enabled", true)) {
            int min = boss
                    ? plugin.getConfig().getInt("gold.drop.boss-min", 250)
                    : plugin.getConfig().getInt("gold.drop.mob-min", 10);
            int max = boss
                    ? plugin.getConfig().getInt("gold.drop.boss-max", 500)
                    : plugin.getConfig().getInt("gold.drop.mob-max", 30);
            int baseGold = plugin.rollInt(min, max);
            long gained = Math.max(1L, Math.round(baseGold * (1.0 + Math.max(0.0, stats.goldFindPct) / 100.0)));
            addGold(killer, gained);

            if (plugin.getConfig().getBoolean("gold.drop.notify-chat", false) || boss) {
                killer.sendMessage(ChatColor.GOLD + "+" + gained + " 골드"
                        + ChatColor.GRAY + " (보유 " + getGold(killer) + "G)");
            }
            plugin.getLogger().info(String.format(Locale.ROOT,
                    "[골드 획득] %s <- %s | 기본 %d x (1 + %.0f%%) = +%dG (보유 %dG)",
                    killer.getName(), dead.getType().name(), baseGold, stats.goldFindPct, gained, getGold(killer)));
        }

        // 2) RPG 장비 드롭 (아이템 발견 확률 연결)
        if (plugin.getConfig().getBoolean("drops.enabled", true)) {
            double baseChance = boss
                    ? plugin.getConfig().getDouble("drops.boss-chance", 100.0)
                    : plugin.getConfig().getDouble("drops.mob-chance", 4.0);
            double mf = Math.max(0.0, stats.magicFindPct);
            double dropChance = baseChance * (1.0 + mf * plugin.getConfig().getDouble("drops.magic-find-drop-scale", 0.5) / 100.0);
            if (random.nextDouble() * 100.0 < dropChance) {
                String category = randomDropCategory();
                ItemStack drop = rollEquipmentByCategory(category, mf, "drops.weights", true);
                if (drop != null) {
                    event.getDrops().add(drop);
                    plugin.getLogger().info("[장비 드롭] " + killer.getName() + " <- " + dead.getType().name()
                            + " | MF=" + String.format(Locale.ROOT, "%.0f%%", mf)
                            + " -> " + drop.getType().name());
                }
            }
        }
    }

    // ===================================================================
    //  등급 / 장비 생성 (도박 & 몬스터 드롭 공용)
    // ===================================================================

    private String randomDropCategory() {
        String[] cats = {"sword", "axe", "bow", "crossbow", "helmet", "armor", "boots", "shield"};
        return cats[random.nextInt(cats.length)];
    }

    private Material rollMaterialForCategory(String category) {
        int roll = random.nextInt(100);
        return switch (category) {
            case "sword" -> roll < 45 ? Material.IRON_SWORD : (roll < 90 ? Material.DIAMOND_SWORD : Material.NETHERITE_SWORD);
            case "axe" -> roll < 45 ? Material.IRON_AXE : (roll < 90 ? Material.DIAMOND_AXE : Material.NETHERITE_AXE);
            case "bow" -> Material.BOW;
            case "crossbow" -> Material.CROSSBOW;
            case "helmet" -> roll < 45 ? Material.IRON_HELMET : (roll < 90 ? Material.DIAMOND_HELMET : Material.NETHERITE_HELMET);
            case "armor" -> roll < 45 ? Material.IRON_CHESTPLATE : (roll < 90 ? Material.DIAMOND_CHESTPLATE : Material.NETHERITE_CHESTPLATE);
            case "boots" -> roll < 45 ? Material.IRON_BOOTS : (roll < 90 ? Material.DIAMOND_BOOTS : Material.NETHERITE_BOOTS);
            case "shield" -> Material.SHIELD;
            default -> Material.IRON_SWORD;
        };
    }

    private String categoryOfMaterial(Material material) {
        String n = material.name();
        if (n.endsWith("_SWORD")) return "sword";
        if (n.endsWith("_AXE")) return "axe";
        if (material == Material.MACE) return "mace";
        if (material == Material.TRIDENT) return "spear";
        if (material == Material.BOW) return "bow";
        if (material == Material.CROSSBOW) return "crossbow";
        if (n.endsWith("_HELMET")) return "helmet";
        if (n.endsWith("_CHESTPLATE")) return "armor";
        if (n.endsWith("_BOOTS")) return "boots";
        if (material == Material.SHIELD) return "shield";
        return "other";
    }

    private ItemStack rollRandomUniqueForCategory(String category) {
        ConfigurationSection uniques = plugin.getConfig().getConfigurationSection("uniques");
        if (uniques == null) return null;
        List<String> matching = new ArrayList<>();
        for (String id : uniques.getKeys(false)) {
            ConfigurationSection u = uniques.getConfigurationSection(id);
            if (u == null) continue;
            Material base = Material.matchMaterial(u.getString("base", ""));
            if (base != null && category.equals(categoryOfMaterial(base))) {
                matching.add(id);
            }
        }
        if (matching.isEmpty()) return null;
        return plugin.createUniqueItem(matching.get(random.nextInt(matching.size())));
    }

    private ItemStack rollRandomSetForCategory(String category) {
        ConfigurationSection sets = plugin.getConfig().getConfigurationSection("sets");
        if (sets == null) return null;
        List<String> matchingRefs = new ArrayList<>();
        for (String setId : sets.getKeys(false)) {
            ConfigurationSection pieces = sets.getConfigurationSection(setId + ".pieces");
            if (pieces == null) continue;
            for (String pieceId : pieces.getKeys(false)) {
                Material base = Material.matchMaterial(pieces.getString(pieceId + ".base", ""));
                if (base != null && category.equals(categoryOfMaterial(base))) {
                    matchingRefs.add(setId + ":" + pieceId);
                }
            }
        }
        if (matchingRefs.isEmpty()) return null;
        List<ItemStack> created = plugin.setSystem().createFromRef(matchingRefs.get(random.nextInt(matchingRefs.size())));
        return created.isEmpty() ? null : created.get(0);
    }

    private String rollRarity(double magicFindPct, String weightSectionPath, boolean allowNormal) {
        ConfigurationSection w = plugin.getConfig().getConfigurationSection(weightSectionPath);
        double normalW = allowNormal ? (w == null ? 55.0 : w.getDouble("normal", 55.0)) : 0.0;
        double magicW = w == null ? (allowNormal ? 34.0 : 88.0) : w.getDouble("magic", allowNormal ? 34.0 : 88.0);
        double rareW = w == null ? 10.0 : w.getDouble("rare", 10.0);
        double setW = w == null ? 1.2 : w.getDouble("set", 1.2);
        double uniqueW = w == null ? 0.8 : w.getDouble("unique", 0.8);

        double mfMult = 1.0 + Math.max(0.0, magicFindPct) / 100.0;
        // MF는 매직/레어/세트/유니크 가중치를 높인다 (원작처럼 유니크/세트는 완만한 효율).
        double effMagic = magicW * (1.0 + (mfMult - 1.0) * 0.5);
        double effRare = rareW * mfMult;
        double effSet = setW * (1.0 + (mfMult - 1.0) * 0.7);
        double effUnique = uniqueW * (1.0 + (mfMult - 1.0) * 0.6);

        double total = normalW + effMagic + effRare + effSet + effUnique;
        if (total <= 0) return allowNormal ? "normal" : "magic";
        double r = random.nextDouble() * total;
        if (r < normalW) return "normal";
        r -= normalW;
        if (r < effMagic) return "magic";
        r -= effMagic;
        if (r < effRare) return "rare";
        r -= effRare;
        if (r < effSet) return "set";
        return "unique";
    }

    private ItemStack rollEquipmentByCategory(String category, double magicFindPct, String weightPath, boolean allowNormal) {
        String rarity = rollRarity(magicFindPct, weightPath, allowNormal);
        if ("unique".equals(rarity)) {
            ItemStack u = rollRandomUniqueForCategory(category);
            if (u != null) return u;
            rarity = "rare";
        } else if ("set".equals(rarity)) {
            ItemStack s = rollRandomSetForCategory(category);
            if (s != null) return s;
            rarity = "rare";
        }
        Material mat = rollMaterialForCategory(category);
        return plugin.createItem(mat, rarity);
    }

    // ===================================================================
    //  상점 / 도박 GUI
    // ===================================================================

    private ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    void openShop(Player player) {
        ShopHolder holder = new ShopHolder();
        Inventory inv = Bukkit.createInventory(holder, INV_SIZE, ChatColor.DARK_GREEN + "상점 · 장비 구매");
        holder.inventory = inv;

        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < INV_SIZE; i++) inv.setItem(i, filler);

        boolean hasEdge = discountFactor(player) < 1.0;
        for (ShopOffer offer : SHOP_OFFERS) {
            long price = effectivePrice(player, offer.price());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "등급: " + ("magic".equals(offer.rarity()) ? ChatColor.AQUA + "매직" : ChatColor.WHITE + "노말"));
            lore.add(ChatColor.GOLD + "가격: " + price + " 골드" + (hasEdge ? ChatColor.GREEN + " (모서리 15% 할인)" : ""));
            lore.add(ChatColor.YELLOW + "클릭하여 구매");
            inv.setItem(offer.slot(), icon(offer.material(), plugin.rarityColor(offer.rarity()) + offer.title(), lore));
        }

        updateGoldIndicator(inv, player);
        inv.setItem(SWITCH_TAB_SLOT, icon(Material.ENDER_EYE, ChatColor.LIGHT_PURPLE + "도박으로 이동",
                List.of(ChatColor.GRAY + "골드를 써서 매직/레어/세트/유니크", ChatColor.GRAY + "장비를 랜덤으로 뽑습니다.")));
        player.openInventory(inv);
    }

    void openGamble(Player player) {
        GambleHolder holder = new GambleHolder();
        Inventory inv = Bukkit.createInventory(holder, INV_SIZE, ChatColor.DARK_PURPLE + "도박 · 랜덤 장비 뽑기");
        holder.inventory = inv;

        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < INV_SIZE; i++) inv.setItem(i, filler);

        boolean hasEdge = discountFactor(player) < 1.0;
        for (GambleOffer offer : GAMBLE_OFFERS) {
            long cfgBase = plugin.getConfig().getLong("gamble.prices." + offer.category(), offer.basePrice());
            long price = effectivePrice(player, cfgBase);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "등급 범위: " + ChatColor.AQUA + "매직 " + ChatColor.GRAY + "/ "
                    + ChatColor.YELLOW + "레어 " + ChatColor.GRAY + "/ "
                    + ChatColor.GREEN + "세트 " + ChatColor.GRAY + "/ " + ChatColor.GOLD + "유니크");
            lore.add(ChatColor.GOLD + "도박 비용: " + price + " 골드" + (hasEdge ? ChatColor.GREEN + " (모서리 15% 할인)" : ""));
            lore.add(ChatColor.YELLOW + "클릭하여 미확인 장비 뽑기");
            inv.setItem(offer.slot(), icon(offer.icon(), ChatColor.GOLD + offer.title(), lore));
        }

        updateGoldIndicator(inv, player);
        inv.setItem(18, icon(Material.EMERALD, ChatColor.GREEN + "상점으로 돌아가기",
                List.of(ChatColor.GRAY + "노말/매직 기본 장비 구매 창으로 이동")));
        player.openInventory(inv);
    }

    private void updateGoldIndicator(Inventory inv, Player player) {
        inv.setItem(GOLD_DISPLAY_SLOT, icon(Material.GOLD_INGOT,
                ChatColor.GOLD + "보유 골드: " + getGold(player) + " G",
                List.of(ChatColor.GRAY + "/rpggold 로 언제든 확인할 수 있습니다.")));
    }

    private void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item).values().forEach(left ->
                player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    // ===================================================================
    //  GUI 클릭 / 블록·NPC 상호작용 리스너
    // ===================================================================

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        boolean isShop = top.getHolder() instanceof ShopHolder;
        boolean isGamble = top.getHolder() instanceof GambleHolder;
        if (!isShop && !isGamble) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int raw = event.getRawSlot();
        if (raw < 0 || raw >= INV_SIZE) return;

        if (isShop) {
            if (raw == SWITCH_TAB_SLOT) {
                openGamble(player);
                return;
            }
            for (ShopOffer offer : SHOP_OFFERS) {
                if (offer.slot() != raw) continue;
                long price = effectivePrice(player, offer.price());
                if (!spendGold(player, price)) {
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    player.sendMessage(ChatColor.RED + "골드가 부족합니다. (필요: " + price + "G, 보유: " + getGold(player) + "G)");
                    return;
                }
                ItemStack bought = plugin.createItem(offer.material(), offer.rarity());
                giveOrDrop(player, bought);
                updateGoldIndicator(top, player);
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.1f);
                player.sendMessage(ChatColor.GREEN + offer.title() + " 구매 완료 (-" + price + "G, 잔액 " + getGold(player) + "G)");
                return;
            }
            return;
        }

        // Gamble GUI
        if (raw == 18) {
            openShop(player);
            return;
        }
        for (GambleOffer offer : GAMBLE_OFFERS) {
            if (offer.slot() != raw) continue;
            long cfgBase = plugin.getConfig().getLong("gamble.prices." + offer.category(), offer.basePrice());
            long price = effectivePrice(player, cfgBase);
            if (!spendGold(player, price)) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                player.sendMessage(ChatColor.RED + "골드가 부족합니다. (필요: " + price + "G, 보유: " + getGold(player) + "G)");
                return;
            }

            double mf = plugin.getConfig().getBoolean("gamble.magic-find-affects", false)
                    ? Math.max(0.0, plugin.statSystem().collect(player).magicFindPct)
                    : 0.0;
            ItemStack rolled = rollEquipmentByCategory(offer.category(), mf, "gamble.weights", false);
            giveOrDrop(player, rolled);
            updateGoldIndicator(top, player);

            String rolledRarity = rolled.hasItemMeta()
                    ? rolled.getItemMeta().getPersistentDataContainer().getOrDefault(plugin.rarityKey, PersistentDataType.STRING, "magic")
                    : "magic";
            String displayName = rolled.hasItemMeta() && rolled.getItemMeta().hasDisplayName()
                    ? rolled.getItemMeta().getDisplayName()
                    : plugin.prettyName(rolled.getType());

            if ("unique".equals(rolledRarity) || "set".equals(rolledRarity)) {
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.1f);
            } else if ("rare".equals(rolledRarity)) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f);
            } else {
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.1f);
            }
            player.sendMessage(ChatColor.LIGHT_PURPLE + "[도박] " + displayName + ChatColor.GRAY
                    + " 획득 (-" + price + "G, 잔액 " + getGold(player) + "G)");
            plugin.getLogger().info("[도박] " + player.getName() + " category=" + offer.category()
                    + " rarity=" + rolledRarity + " cost=" + price);
            return;
        }
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof ShopHolder || top.getHolder() instanceof GambleHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onShopBlockInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Material shopBlock = Material.matchMaterial(plugin.getConfig().getString("shop.block", "GOLD_BLOCK"));
        if (shopBlock == null || block.getType() != shopBlock) return;
        Player player = event.getPlayer();
        if (player.isSneaking()) return;
        event.setCancelled(true);
        openShop(player);
    }

    private boolean isMerchant(Entity entity) {
        return entity instanceof Villager
                && entity.getPersistentDataContainer().has(merchantKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onMerchantInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!isMerchant(event.getRightClicked())) return;
        event.setCancelled(true);
        openShop(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMerchantDamaged(EntityDamageEvent event) {
        if (isMerchant(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    // ===================================================================
    //  명령어 (/rpggold, /rpgshop, /rpggamble)
    // ===================================================================

    boolean handleGoldCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.YELLOW + "사용법: /rpggold give|set <플레이어> <수량>");
                return true;
            }
            StatSystem.Stats s = plugin.statSystem().collect(p);
            p.sendMessage(ChatColor.GOLD + "보유 골드: " + getGold(p) + " G");
            p.sendMessage(String.format(Locale.ROOT, "%s골드 획득량 +%.0f%%  |  아이템 발견 확률 +%.0f%%",
                    ChatColor.GRAY, s.goldFindPct, s.magicFindPct));
            return true;
        }
        if (!sender.hasPermission("diablorpg.admin")) {
            sender.sendMessage(ChatColor.RED + "권한이 없습니다.");
            return true;
        }
        if (args.length != 3 || (!args[0].equalsIgnoreCase("give") && !args[0].equalsIgnoreCase("set"))) {
            sender.sendMessage(ChatColor.YELLOW + "사용법: /rpggold give|set <플레이어> <수량>");
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "플레이어를 찾을 수 없습니다.");
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "수량은 정수로 입력하세요.");
            return true;
        }
        if (amount < 0) {
            sender.sendMessage(ChatColor.RED + "0 이상의 숫자를 입력하세요.");
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            addGold(target, amount);
            sender.sendMessage(ChatColor.GREEN + target.getName() + "에게 " + amount + "G 지급 (보유 " + getGold(target) + "G)");
        } else {
            setGold(target, amount);
            sender.sendMessage(ChatColor.GREEN + target.getName() + "의 골드를 " + amount + "G로 설정했습니다.");
        }
        return true;
    }

    boolean handleShopCommand(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("spawn")) {
            if (!sender.hasPermission("diablorpg.admin")) {
                sender.sendMessage(ChatColor.RED + "권한이 없습니다.");
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "플레이어만 소환할 수 있습니다.");
                return true;
            }
            Location base = player.getLocation();
            Vector dir = base.getDirection().setY(0);
            if (dir.lengthSquared() < 1.0E-6) dir = new Vector(0, 0, 1);
            Location loc = base.clone().add(dir.normalize().multiply(2.5));
            loc.setY(base.getY());
            loc.setYaw(base.getYaw() + 180f);
            loc.setPitch(0f);

            Villager npc = (Villager) player.getWorld().spawnEntity(loc, EntityType.VILLAGER);
            npc.setAI(false);
            npc.setInvulnerable(true);
            npc.setSilent(true);
            npc.setPersistent(true);
            npc.setRemoveWhenFarAway(false);
            npc.setProfession(Villager.Profession.WEAPONSMITH);
            npc.setVillagerLevel(5);
            npc.setCustomName(ChatColor.GOLD + "[상점/도박] 기드");
            npc.setCustomNameVisible(true);
            npc.getPersistentDataContainer().set(merchantKey, PersistentDataType.BYTE, (byte) 1);
            sender.sendMessage(ChatColor.GREEN + "상인 NPC(기드)를 소환했습니다. 우클릭하면 상점/도박이 열립니다.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("clear")) {
            if (!sender.hasPermission("diablorpg.admin")) {
                sender.sendMessage(ChatColor.RED + "권한이 없습니다.");
                return true;
            }
            int removed = 0;
            for (World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntities()) {
                    if (isMerchant(e)) {
                        e.remove();
                        removed++;
                    }
                }
            }
            sender.sendMessage(ChatColor.GREEN + "상인 NPC " + removed + "명을 제거했습니다.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "게임 안의 플레이어만 상점을 열 수 있습니다.");
            return true;
        }
        openShop(player);
        return true;
    }

    boolean handleGambleCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "게임 안의 플레이어만 도박을 열 수 있습니다.");
            return true;
        }
        openGamble(player);
        return true;
    }
}
