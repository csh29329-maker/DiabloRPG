package com.example.diablorpg;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 룬 / 큐브 / 룬워드 (0.4.0, 전부 미검증).
 *
 *  - 룬 33종 아이템 (기반 아이템 + CustomModelData 1~33 + PDC 에 룬 이름).
 *  - 몬스터 처치 시 룬 드롭 (난이도별 최대 룬, 번호가 높을수록 드물게).
 *  - 큐브: 설정한 블록을 우클릭하면 27칸 창이 열린다.
 *      10번 칸 = 장비, 12~17번 칸 = 룬(최대 6개), 22번 칸 = 제작 버튼.
 *  - 룬워드: config.yml 의 runewords 섹션. 룬 순서는 상관없고 장비는 노말 등급이어야 한다.
 */
final class RuneSystem implements Listener {

    /** 룬 33종. 배열 순서 = 룬 번호(1~33). */
    static final String[] RUNES = {
            "El", "Eld", "Tir", "Nef", "Eth", "Ith", "Tal", "Ral", "Ort", "Thul", "Amn",
            "Sol", "Shael", "Dol", "Hel", "Io", "Lum", "Ko", "Fal", "Lem", "Pul", "Um",
            "Mal", "Ist", "Gul", "Vex", "Ohm", "Lo", "Sur", "Ber", "Jah", "Cham", "Zod"
    };

    private static final int INV_SIZE = 27;
    private static final int EQUIP_SLOT = 10;
    private static final int[] RUNE_SLOTS = {12, 13, 14, 15, 16, 17};
    private static final int BUTTON_SLOT = 22;

    private final DiabloRPG plugin;
    private final NamespacedKey runeKey;
    private final NamespacedKey runewordKey;
    private final Random random = new Random();

    RuneSystem(DiabloRPG plugin) {
        this.plugin = plugin;
        this.runeKey = new NamespacedKey(plugin, "rune_name");
        this.runewordKey = new NamespacedKey(plugin, "runeword_id");
    }

    /** 큐브 창을 식별하기 위한 홀더. */
    private static final class CubeHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ===================================================================
    //  룬 아이템
    // ===================================================================

    /** 룬 이름(대소문자 무관) → 번호(1~33). 없으면 -1. */
    static int runeIndex(String name) {
        if (name == null) return -1;
        for (int i = 0; i < RUNES.length; i++) {
            if (RUNES[i].equalsIgnoreCase(name)) return i + 1;
        }
        return -1;
    }

    private ChatColor runeColor(int index) {
        if (index <= 8) return ChatColor.WHITE;
        if (index <= 18) return ChatColor.AQUA;
        if (index <= 28) return ChatColor.GOLD;
        return ChatColor.RED;
    }

    ItemStack createRune(String name, int amount) {
        int index = runeIndex(name);
        if (index < 0) return null;
        String canonical = RUNES[index - 1];

        Material material = Material.matchMaterial(plugin.getConfig().getString("runes.item", "AMETHYST_SHARD"));
        if (material == null || !material.isItem()) material = Material.AMETHYST_SHARD;

        ItemStack item = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(runeColor(index) + canonical + " 룬");
        meta.setCustomModelData(index);
        meta.getPersistentDataContainer().set(runeKey, PersistentDataType.STRING, canonical);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "룬 #" + index);
        lore.add(ChatColor.GRAY + "큐브에서 룬워드를 만드는 데 쓴다.");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** 룬 아이템이면 룬 이름, 아니면 null. */
    private String runeNameOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(runeKey, PersistentDataType.STRING);
    }

    private boolean isRune(ItemStack item) {
        return runeNameOf(item) != null;
    }

    // ===================================================================
    //  룬 드롭
    // ===================================================================

    private boolean isBoss(EntityType type) {
        return switch (type) {
            case WITHER, ENDER_DRAGON, WARDEN, ELDER_GUARDIAN -> true;
            default -> false;
        };
    }

    /** 현재 난이도에서 드롭 가능한 가장 높은 룬 번호. */
    private int maxRuneIndex() {
        String difficulty = plugin.getConfig().getString("runes.difficulty", "normal");
        String name = plugin.getConfig().getString("runes.max-rune." + difficulty, "Ral");
        int index = runeIndex(name);
        return index < 1 ? 8 : index;
    }

    private String rollRune() {
        int max = maxRuneIndex();
        double decay = plugin.getConfig().getDouble("runes.drop.decay", 0.8);
        double[] weights = new double[max];
        double total = 0.0;
        for (int i = 0; i < max; i++) {
            weights[i] = Math.pow(decay, i);
            total += weights[i];
        }
        double r = random.nextDouble() * total;
        for (int i = 0; i < max; i++) {
            r -= weights[i];
            if (r < 0) return RUNES[i];
        }
        return RUNES[max - 1];
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player) return;
        Player killer = dead.getKiller();
        if (killer == null) return;
        if (plugin.isDummy(dead)) return;

        boolean boss = isBoss(dead.getType());
        if (!boss && !(dead instanceof Enemy)) return;

        double chance = boss
                ? plugin.getConfig().getDouble("runes.drop.boss-chance", 25.0)
                : plugin.getConfig().getDouble("runes.drop.chance", 2.0);
        if (random.nextDouble() * 100.0 >= chance) return;

        String rune = rollRune();
        ItemStack item = createRune(rune, 1);
        if (item == null) return;
        event.getDrops().add(item);
        plugin.getLogger().info("[룬 드롭] " + killer.getName() + " 이(가) " + dead.getType().name()
                + " 처치 -> " + rune + " (boss=" + boss + ", difficulty="
                + plugin.getConfig().getString("runes.difficulty", "normal") + ")");
    }

    // ===================================================================
    //  큐브 창
    // ===================================================================

    private boolean isInputSlot(int slot) {
        if (slot == EQUIP_SLOT) return true;
        for (int s : RUNE_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void openCube(Player player) {
        CubeHolder holder = new CubeHolder();
        Inventory inv = Bukkit.createInventory(holder, INV_SIZE, ChatColor.DARK_PURPLE + "큐브");
        holder.inventory = inv;

        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < INV_SIZE; i++) inv.setItem(i, filler);
        inv.setItem(EQUIP_SLOT, null);
        for (int s : RUNE_SLOTS) inv.setItem(s, null);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "왼쪽 칸: 노말 등급 장비 1개");
        lore.add(ChatColor.GRAY + "오른쪽 칸: 룬 (순서 상관없음, 최대 6개)");
        lore.add(ChatColor.GRAY + "클릭하면 일치하는 룬워드를 제작한다.");
        inv.setItem(BUTTON_SLOT, named(Material.ANVIL, ChatColor.GREEN + "룬워드 제작", lore));

        player.openInventory(inv);
    }

    @EventHandler
    public void onCubeInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Material cubeBlock = Material.matchMaterial(plugin.getConfig().getString("cube.block", "LODESTONE"));
        if (cubeBlock == null || block.getType() != cubeBlock) return;
        Player player = event.getPlayer();
        if (player.isSneaking()) return; // 웅크리면 평소처럼 블록 설치/사용
        event.setCancelled(true);
        openCube(player);
    }

    /** 놓으려는 아이템이 이 칸에 들어갈 수 있는가. 장비 칸 = 룬이 아닌 것, 룬 칸 = 룬만. */
    private boolean acceptable(int slot, ItemStack item) {
        if (item == null || item.getType().isAir()) return true;
        if (slot == EQUIP_SLOT) return !isRune(item);
        return isRune(item);
    }

    /** 시프트 클릭으로 옮길 때 들어갈 빈 칸. 없으면 -1. */
    private int findTargetSlot(Inventory top, ItemStack moving) {
        if (isRune(moving)) {
            for (int s : RUNE_SLOTS) {
                ItemStack cur = top.getItem(s);
                if (cur == null || cur.getType().isAir()) return s;
            }
            return -1;
        }
        ItemStack cur = top.getItem(EQUIP_SLOT);
        return (cur == null || cur.getType().isAir()) ? EQUIP_SLOT : -1;
    }

    @EventHandler
    public void onCubeClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CubeHolder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int raw = event.getRawSlot();
        boolean inTop = raw >= 0 && raw < INV_SIZE;
        ClickType click = event.getClick();
        InventoryAction action = event.getAction();

        // 더블클릭 모으기/복제 등은 창 안의 아이템을 빼낼 수 있어 전부 막는다.
        if (click == ClickType.DOUBLE_CLICK
                || action == InventoryAction.COLLECT_TO_CURSOR
                || action == InventoryAction.CLONE_STACK
                || action == InventoryAction.UNKNOWN) {
            event.setCancelled(true);
            return;
        }

        // 플레이어 인벤토리 칸: 시프트 클릭만 직접 처리한다. 나머지는 평소처럼 동작.
        if (!inTop) {
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
                ItemStack moving = event.getCurrentItem();
                if (moving == null || moving.getType().isAir()) return;
                int target = findTargetSlot(top, moving);
                if (target >= 0) {
                    top.setItem(target, moving.clone());
                    event.setCurrentItem(null);
                }
            }
            return;
        }

        // 제작 버튼
        if (raw == BUTTON_SLOT) {
            event.setCancelled(true);
            if (click == ClickType.LEFT || click == ClickType.RIGHT
                    || click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
                tryCraft(player, top);
            }
            return;
        }

        // 입력 칸이 아닌 곳(유리판)은 건드릴 수 없다.
        if (!isInputSlot(raw)) {
            event.setCancelled(true);
            return;
        }

        // 입력 칸에서 시프트 클릭: 플레이어 인벤토리로 돌려준다.
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            ItemStack item = top.getItem(raw);
            if (item != null && !item.getType().isAir()) {
                top.setItem(raw, null);
                giveBack(player, item);
            }
            return;
        }

        // 놓으려는 아이템 검사
        ItemStack incoming = null;
        switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> incoming = event.getCursor();
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (click == ClickType.SWAP_OFFHAND) {
                    incoming = player.getInventory().getItemInOffHand();
                } else if (event.getHotbarButton() >= 0) {
                    incoming = player.getInventory().getItem(event.getHotbarButton());
                }
            }
            default -> { /* 꺼내기/버리기 등은 허용 */ }
        }
        if (!acceptable(raw, incoming)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCubeDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CubeHolder)) return;
        for (int raw : event.getRawSlots()) {
            if (raw >= 0 && raw < INV_SIZE) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onCubeClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof CubeHolder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        List<Integer> slots = new ArrayList<>();
        slots.add(EQUIP_SLOT);
        for (int s : RUNE_SLOTS) slots.add(s);
        for (int slot : slots) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            inv.setItem(slot, null);
            giveBack(player, item);
        }
    }

    private void giveBack(Player player, ItemStack item) {
        player.getInventory().addItem(item).values().forEach(left ->
                player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    // ===================================================================
    //  룬워드 제작
    // ===================================================================

    /** 마인크래프트 아이템 → 장비 종류. 룬워드 대상이 아니면 null. */
    private String categoryOf(Material material) {
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
        return null;
    }

    /** types 목록(종류 또는 묶음 이름)에 이 장비 종류가 들어가는가. */
    private boolean typeMatches(List<String> types, String category) {
        for (String t : types) {
            if (t.equalsIgnoreCase(category)) return true;
            List<String> group = plugin.getConfig().getStringList("item-groups." + t);
            for (String g : group) {
                if (g.equalsIgnoreCase(category)) return true;
            }
        }
        return false;
    }

    private List<String> sortedRunes(List<String> names) {
        List<String> list = new ArrayList<>();
        for (String n : names) {
            int idx = runeIndex(n);
            if (idx > 0) list.add(RUNES[idx - 1]);
        }
        list.sort((a, b) -> Integer.compare(runeIndex(a), runeIndex(b)));
        return list;
    }

    private void tryCraft(Player player, Inventory inv) {
        ItemStack base = inv.getItem(EQUIP_SLOT);
        if (base == null || base.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "왼쪽 칸에 장비를 올려 주세요.");
            return;
        }
        if (base.getAmount() != 1) {
            player.sendMessage(ChatColor.RED + "장비는 1개만 올릴 수 있습니다.");
            return;
        }
        String category = categoryOf(base.getType());
        if (category == null) {
            player.sendMessage(ChatColor.RED + "이 아이템은 룬워드 대상 장비가 아닙니다.");
            return;
        }
        ItemMeta baseMeta = base.getItemMeta();
        String rarity = baseMeta == null ? null
                : baseMeta.getPersistentDataContainer().get(plugin.rarityKey, PersistentDataType.STRING);
        if (rarity != null && !rarity.equals("normal")) {
            player.sendMessage(ChatColor.RED + "노말 등급 장비만 룬워드로 만들 수 있습니다.");
            return;
        }

        List<String> placed = new ArrayList<>();
        for (int slot : RUNE_SLOTS) {
            String rune = runeNameOf(inv.getItem(slot));
            if (rune != null) placed.add(rune);
        }
        if (placed.isEmpty()) {
            player.sendMessage(ChatColor.RED + "오른쪽 칸에 룬을 올려 주세요.");
            return;
        }
        List<String> sortedPlaced = sortedRunes(placed);

        ConfigurationSection all = plugin.getConfig().getConfigurationSection("runewords");
        if (all == null) {
            player.sendMessage(ChatColor.RED + "config.yml 에 runewords 섹션이 없습니다.");
            return;
        }

        ConfigurationSection matched = null;
        String matchedId = null;
        for (String id : all.getKeys(false)) {
            ConfigurationSection rw = all.getConfigurationSection(id);
            if (rw == null) continue;
            List<String> recipe = sortedRunes(rw.getStringList("runes"));
            if (!recipe.isEmpty() && recipe.equals(sortedPlaced)) {
                matched = rw;
                matchedId = id;
                break;
            }
        }
        if (matched == null) {
            player.sendMessage(ChatColor.RED + "이 룬 조합에 맞는 룬워드가 없습니다.");
            return;
        }

        ConfigurationSection variant = null;
        ConfigurationSection variants = matched.getConfigurationSection("variants");
        if (variants != null) {
            for (String key : variants.getKeys(false)) {
                ConfigurationSection v = variants.getConfigurationSection(key);
                if (v != null && typeMatches(v.getStringList("types"), category)) {
                    variant = v;
                    break;
                }
            }
        }
        if (variant == null) {
            player.sendMessage(ChatColor.RED + matched.getString("name", matchedId)
                    + " 은(는) 이 장비 종류(" + category + ")에는 만들 수 없습니다.");
            return;
        }

        ItemStack result = buildRuneword(base, matchedId, matched, variant, sortedPlaced);

        // 룬 1개씩 소모
        for (int slot : RUNE_SLOTS) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.getType().isAir() || !isRune(stack)) continue;
            if (stack.getAmount() <= 1) {
                inv.setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - 1);
                inv.setItem(slot, stack);
            }
        }
        inv.setItem(EQUIP_SLOT, result);

        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
        player.sendMessage(ChatColor.LIGHT_PURPLE + matched.getString("name", matchedId) + ChatColor.GREEN
                + " 룬워드를 만들었습니다.");
        plugin.getLogger().info("[룬워드 제작] " + player.getName() + " -> " + matchedId + " on "
                + base.getType().name() + " runes=" + String.join("+", sortedPlaced));
    }

    private ItemStack buildRuneword(ItemStack base, String id, ConfigurationSection rw,
                                    ConfigurationSection variant, List<String> runes) {
        ItemStack item = base.clone();
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(plugin.rarityKey, PersistentDataType.STRING, "runeword");
        pdc.set(runewordKey, PersistentDataType.STRING, id);

        meta.setDisplayName(plugin.rarityColor("runeword") + rw.getString("name", id));

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "등급: "
                + plugin.getConfig().getString("rarities.runeword.display", "룬워드"));
        lore.add(ChatColor.DARK_GRAY + plugin.prettyName(base.getType()));
        lore.add(ChatColor.LIGHT_PURPLE + "룬: " + String.join(" + ", runes));

        ConfigurationSection options = variant.getConfigurationSection("options");
        if (options != null) {
            for (String optName : options.getKeys(false)) {
                ConfigurationSection opt = options.getConfigurationSection(optName);
                if (opt == null) continue;
                int min = opt.getInt("min", 0);
                int value = plugin.rollInt(min, opt.getInt("max", min));
                lore.add(ChatColor.BLUE + optName + " +" + value + opt.getString("suffix", ""));
                plugin.applyOptionToPdc(meta, optName, value);
            }
        }
        for (String line : variant.getStringList("inactive")) {
            lore.add(ChatColor.DARK_GRAY + line + " (효과 없음)");
        }

        // 최대 생명력/공격 속도/이동 속도는 AttributeModifier 로 장비에 부착.
        plugin.applyAttributeModifiers(meta);

        lore.add(ChatColor.DARK_GRAY + "DiabloRPG " + DiabloRPG.VERSION);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ===================================================================
    //  명령어
    // ===================================================================

    boolean handleCubeCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "게임 안의 플레이어만 큐브를 열 수 있습니다.");
            return true;
        }
        openCube(player);
        return true;
    }

    boolean handleRuneCommand(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < RUNES.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(i + 1).append(':').append(RUNES[i]);
            }
            sender.sendMessage(ChatColor.YELLOW + "룬 목록: " + sb);
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("recipes")) {
            ConfigurationSection all = plugin.getConfig().getConfigurationSection("runewords");
            if (all == null || all.getKeys(false).isEmpty()) {
                sender.sendMessage(ChatColor.RED + "등록된 룬워드가 없습니다.");
                return true;
            }
            for (String id : all.getKeys(false)) {
                ConfigurationSection rw = all.getConfigurationSection(id);
                if (rw == null) continue;
                sender.sendMessage(ChatColor.LIGHT_PURPLE + rw.getString("name", id) + ChatColor.GRAY + " : "
                        + String.join(" + ", sortedRunes(rw.getStringList("runes"))));
            }
            return true;
        }

        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(ChatColor.YELLOW + "사용법: /rpgrune give <플레이어> <룬|all> [개수]");
            sender.sendMessage(ChatColor.YELLOW + "        /rpgrune list      (룬 목록)");
            sender.sendMessage(ChatColor.YELLOW + "        /rpgrune recipes   (룬워드 레시피)");
            return true;
        }

        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "플레이어를 찾을 수 없습니다.");
            return true;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "개수는 숫자로 입력하세요.");
                return true;
            }
            if (amount < 1 || amount > 64) {
                sender.sendMessage(ChatColor.RED + "개수는 1~64 사이로 입력하세요.");
                return true;
            }
        }

        if (args[2].equalsIgnoreCase("all")) {
            for (String rune : RUNES) {
                giveBack(target, createRune(rune, amount));
            }
            sender.sendMessage(ChatColor.GREEN + target.getName() + "에게 룬 33종을 " + amount + "개씩 지급했습니다.");
            return true;
        }

        ItemStack rune = createRune(args[2].toLowerCase(Locale.ROOT), amount);
        if (rune == null) {
            sender.sendMessage(ChatColor.RED + "알 수 없는 룬입니다. /rpgrune list 로 확인하세요.");
            return true;
        }
        giveBack(target, rune);
        sender.sendMessage(ChatColor.GREEN + target.getName() + "에게 " + args[2] + " 룬 " + amount + "개를 지급했습니다.");
        return true;
    }
}
