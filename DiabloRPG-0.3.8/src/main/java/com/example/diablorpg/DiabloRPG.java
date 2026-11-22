package com.example.diablorpg;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * DiabloRPG 메인 클래스.
 *
 * 0.3.0 변경 요약:
 *  - 0.2.0 기능(노말/매직/레어 생성, 공격력 증가 % 적용)을 그대로 보존한다.
 *  - 공격 피해 파이프라인을 확장하여 아래 옵션이 실제 전투에 연결된다.
 *      [적용] 물리 피해 추가 / 화염·냉기·번개·독·비전 피해 추가 / 원소 피해 전환
 *             / 생명력 흡수 / 대상 방어력 감소 / 공격력 증가(보존)
 *      [로직, 미검증] 치명타 확률 / 강타 확률
 *        (유니크·세트·룬워드 전용 옵션으로, 0.3.0에는 테스트 아이템이 없어 미검증)
 *  - 공격 시 피해 항목별 분석을 콘솔에 출력한다.
 *  - unique / set / runeword 등급은 명령어 인수로 받되 "미구현" 응답만 한다.
 *
 * 미구현(후속 버전): 능력치 옵션, 처치 시 회복,
 *   시전 속도, 회피, 저항 감소, 오라, 스킬 발동,
 *   유니크/세트/룬워드 생성, 룬 드롭, 룬워드 제작.
 *
 * 0.3.0 방어 묶음 추가:
 *  - 방어력 증가 / 물리 저항 / 화염·냉기·번개·독·비전 저항 / 모든 원소 저항 / 피해 감소(고정) 적용.
 *  - 몹에게 종류별 가상 방어력%를 부여하여 "대상 방어력 감소" 옵션이 일반 몹에도 체감되도록 개선.
 *  - 플레이어가 피해를 받을 때 갑옷 4칸에서 방어 통계를 합산해 적용하는 피해자 리스너 추가.
 *
 * 0.3.1 능력치 묶음 추가:
 *  - 최대 생명력 / 공격 속도 증가 / 이동 속도 증가를 AttributeModifier로 장비에 직접 부착.
 *    마인크래프트가 장비 입/벗을 때 자동으로 적용·복구하므로 별도 리스너 불필요.
 *  - 테스트 명령어 /rpgtest에 health / atkspeed / movespeed 세 가지 추가.
 *  - 랜덤 생성은 종류 무관 전체 옵션 풀에서 롤 (갑옷에 공격 옵션, 검에 방어 옵션도 허용).
 *
 * 0.3.8 1.21.1 API 이전: 빌드 대상을 Paper API 1.21.1로 낮췄다 (모드 하이브리드 서버 Arclight 1.21.1 이전 대비).
 *  - 버전마다 이름이 다른 Attribute 상수는 VersionCompat 한 곳에서만 참조한다. 기능 변경 없음. [미검증]
 *
 * 0.3.7 샌드백: /rpgdummy spawn <normal|undead|demon> [방어%] / clear. 맞아도 죽지 않고 피해를 이름표에 표시. [미검증]
 *
 * 0.3.6 유니크 아이템: config.yml의 uniques 섹션으로 정의, /rpgitem give <플레이어> unique <ID>.
 *  - 최소~최대 범위 피해(damage-ranges)를 타격마다 다시 굴린다. [미검증]
 *
 * 0.3.5 수정: 음수 저항만 있을 때 피해자 핸들러가 건너뛰던 버그 수정 (hasAny가 > 0만 검사).
 *
 * 0.3.4 투사체/저항 묶음:
 *  - 활/석궁 발사 시 무기 옵션을 화살에 복사하고, 명중 시 같은 피해 파이프라인으로 계산한다. [미검증]
 *  - 저항 상한 75%(config resist.max), 음수 저항 허용(하한 resist.min=-100). [미검증]
 *  - 대상 방어력 감소는 양수 방어/저항에만 적용하고 음수 저항은 건드리지 않는다.
 *  - 테스트 명령어 /rpgtest에 bow / crossbow / bowmix / overres / negres 추가.
 *
 * 0.3.2 대상 특화 묶음 추가:
 *  - 악마 추가 피해 / 언데드 추가 피해: 대상 몹 종류 판정 후 물리 피해에 % 추가.
 *  - 대상 둔화: 공격 시 % 확률로 대상에게 Slowness(amplifier 1, 5초) 부여.
 *  - 테스트 명령어 /rpgtest에 demon / undead / slow 세 가지 추가.
 */
public final class DiabloRPG extends JavaPlugin implements Listener {

    private final Random random = new Random();

    // ===== 희귀도 상수 =====
    private static final List<String> ALL_RARITIES =
            Arrays.asList("normal", "magic", "rare", "unique", "set", "runeword");
    private static final List<String> ROLLABLE_RARITIES =
            Arrays.asList("normal", "magic", "rare");
    private static final List<String> UNIMPLEMENTED_RARITIES =
            Arrays.asList("set", "runeword");

    // 기본 원소 목록 (config에 elements가 없을 때의 폴백)
    private static final List<String> DEFAULT_ELEMENTS =
            Arrays.asList("화염", "냉기", "번개", "독", "비전");

    // 랜덤 무기 풀 (random_weapon 키워드로 롤) — 상위 티어 무기만.
    // 하위 티어(나무/돌/금)는 제외하여 레어 등급에 어울리는 무기가 나오도록 한다.
    private static final List<Material> RANDOM_WEAPONS =
            Arrays.asList(Material.IRON_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD,
                    Material.IRON_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE,
                    Material.BOW, Material.CROSSBOW, Material.TRIDENT);

    // ===== PersistentDataContainer 키 =====
    // 주의: attackBonusKey는 0.2.0의 "attack_damage_percent" 키 이름을 그대로 유지하여
    // 0.2.0에서 생성된 아이템과의 호환성을 보존한다.
    private NamespacedKey rarityKey;
    private NamespacedKey attackBonusKey;          // 공격력 증가 %  (0.2.0 보존)
    private NamespacedKey flatPhysicalKey;         // 물리 피해 추가
    private NamespacedKey flatFireKey;             // 화염 피해 추가
    private NamespacedKey flatColdKey;             // 냉기 피해 추가
    private NamespacedKey flatLightningKey;        // 번개 피해 추가
    private NamespacedKey flatPoisonKey;           // 독 피해 추가
    private NamespacedKey flatArcaneKey;           // 비전 피해 추가
    private NamespacedKey convertPctKey;           // 원소 피해 전환 %
    private NamespacedKey convertElementKey;       // 원소 피해 전환 대상 원소
    private NamespacedKey critChanceKey;           // 치명타 확률 %  (전용, 미검증)
    private NamespacedKey crushingChanceKey;       // 강타 확률 %    (전용, 미검증)
    private NamespacedKey lifeLeechKey;            // 생명력 흡수 %
    private NamespacedKey defReductionKey;         // 대상 방어력 감소 %

    // ===== 방어/저항 PDC 키 (갑옷에 부착) =====
    private NamespacedKey defensePctKey;           // 방어력 증가 % (모든 피해 감소)
    private NamespacedKey physResistKey;           // 물리 저항 %
    private NamespacedKey fireResistKey;           // 화염 저항 %
    private NamespacedKey coldResistKey;           // 냉기 저항 %
    private NamespacedKey lightningResistKey;      // 번개 저항 %
    private NamespacedKey poisonResistKey;         // 독 저항 %
    private NamespacedKey arcaneResistKey;         // 비전 저항 %
    private NamespacedKey allElemResistKey;        // 모든 원소 저항 %
    private NamespacedKey flatDmgReduceKey;        // 피해 감소 (고정)

    // ===== 능력치 PDC 키 (0.3.1: AttributeModifier로 자동 적용) =====
    private NamespacedKey maxHealthKey;            // 최대 생명력 (고정 수치, ADD_NUMBER)
    private NamespacedKey attackSpeedKey;          // 공격 속도 증가 % (기본 공속에 비례해 ADD_NUMBER)
    private NamespacedKey moveSpeedKey;            // 이동 속도 증가 % (MULTIPLY)

    // ===== 대상 특화 PDC 키 (0.3.2) =====
    private NamespacedKey demonDmgKey;             // 악마 추가 피해 % (물리에 곱적용)
    private NamespacedKey undeadDmgKey;            // 언데드 추가 피해 % (물리에 곱적용)
    private NamespacedKey slowPctKey;              // 대상 둔화 % (확률로 Slowness 부여)

    // ===== 샌드백 PDC 키 (0.3.7) =====
    private NamespacedKey dummyKey;                // 샌드백 표식 + 종류(normal/undead/demon)
    private NamespacedKey dummyDefenseKey;         // 샌드백 가상 방어력 %
    private NamespacedKey dummyTotalKey;           // 누적 피해
    private NamespacedKey dummyHitsKey;            // 타격 횟수

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rarityKey = new NamespacedKey(this, "rarity");
        attackBonusKey = new NamespacedKey(this, "attack_damage_percent");
        flatPhysicalKey = new NamespacedKey(this, "flat_physical");
        flatFireKey = new NamespacedKey(this, "flat_fire");
        flatColdKey = new NamespacedKey(this, "flat_cold");
        flatLightningKey = new NamespacedKey(this, "flat_lightning");
        flatPoisonKey = new NamespacedKey(this, "flat_poison");
        flatArcaneKey = new NamespacedKey(this, "flat_arcane");
        convertPctKey = new NamespacedKey(this, "convert_pct");
        convertElementKey = new NamespacedKey(this, "convert_element");
        critChanceKey = new NamespacedKey(this, "crit_chance");
        crushingChanceKey = new NamespacedKey(this, "crushing_chance");
        lifeLeechKey = new NamespacedKey(this, "life_leech_pct");
        defReductionKey = new NamespacedKey(this, "target_def_reduction_pct");
        defensePctKey = new NamespacedKey(this, "defense_pct");
        physResistKey = new NamespacedKey(this, "phys_resist_pct");
        fireResistKey = new NamespacedKey(this, "fire_resist_pct");
        coldResistKey = new NamespacedKey(this, "cold_resist_pct");
        lightningResistKey = new NamespacedKey(this, "lightning_resist_pct");
        poisonResistKey = new NamespacedKey(this, "poison_resist_pct");
        arcaneResistKey = new NamespacedKey(this, "arcane_resist_pct");
        allElemResistKey = new NamespacedKey(this, "all_elem_resist_pct");
        flatDmgReduceKey = new NamespacedKey(this, "flat_dmg_reduce");
        maxHealthKey = new NamespacedKey(this, "max_health_flat");
        attackSpeedKey = new NamespacedKey(this, "attack_speed_pct");
        moveSpeedKey = new NamespacedKey(this, "move_speed_pct");
        demonDmgKey = new NamespacedKey(this, "demon_dmg_pct");
        undeadDmgKey = new NamespacedKey(this, "undead_dmg_pct");
        slowPctKey = new NamespacedKey(this, "slow_pct");
        dummyKey = new NamespacedKey(this, "dummy_kind");
        dummyDefenseKey = new NamespacedKey(this, "dummy_defense_pct");
        dummyTotalKey = new NamespacedKey(this, "dummy_total_damage");
        dummyHitsKey = new NamespacedKey(this, "dummy_hits");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("DiabloRPG 0.3.8 enabled.");
    }

    // ===================================================================
    //  전투: 공격 피해 파이프라인
    // ===================================================================

    @EventHandler
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        // 옵션 출처: (1) 플레이어가 손에 든 무기(근접), (2) 플레이어가 쏜 화살에 복사된 옵션(0.3.4).
        final Player player;
        final PersistentDataContainer pdc;
        final boolean ranged;
        final String via;

        Entity damager = event.getDamager();
        if (damager instanceof Player p) {
            ItemStack weapon = p.getInventory().getItemInMainHand();
            if (weapon.getType().isAir() || !weapon.hasItemMeta()) return;
            pdc = weapon.getItemMeta().getPersistentDataContainer();
            // RPG 아이템이 아니면(일반 바닐라 무기) 간섭하지 않는다.
            if (!pdc.has(rarityKey, PersistentDataType.STRING)) return;
            player = p;
            ranged = isRangedWeapon(weapon.getType());
            via = "hand";
        } else if (damager instanceof AbstractArrow arrow && !(arrow instanceof Trident)
                && arrow.getShooter() instanceof Player shooter) {
            // 발사 시점에 화살 PDC로 복사해 둔 옵션을 읽는다. (복사된 게 없으면 바닐라 화살)
            pdc = arrow.getPersistentDataContainer();
            if (!pdc.has(rarityKey, PersistentDataType.STRING)) return;
            player = shooter;
            ranged = true;
            via = "projectile";
        } else {
            return;
        }

        // 무기에서 모든 피해 관련 수치를 읽는다.
        double attackPct = pdc.getOrDefault(attackBonusKey, PersistentDataType.DOUBLE, 0.0);
        double flatPhys = pdc.getOrDefault(flatPhysicalKey, PersistentDataType.DOUBLE, 0.0);
        double flatFire = pdc.getOrDefault(flatFireKey, PersistentDataType.DOUBLE, 0.0);
        double flatCold = pdc.getOrDefault(flatColdKey, PersistentDataType.DOUBLE, 0.0);
        double flatLightning = pdc.getOrDefault(flatLightningKey, PersistentDataType.DOUBLE, 0.0);
        double flatPoison = pdc.getOrDefault(flatPoisonKey, PersistentDataType.DOUBLE, 0.0);
        double flatArcane = pdc.getOrDefault(flatArcaneKey, PersistentDataType.DOUBLE, 0.0);
        double convertPct = pdc.getOrDefault(convertPctKey, PersistentDataType.DOUBLE, 0.0);
        String convertElement = pdc.get(convertElementKey, PersistentDataType.STRING);
        double critChance = pdc.getOrDefault(critChanceKey, PersistentDataType.DOUBLE, 0.0);
        double crushingChance = pdc.getOrDefault(crushingChanceKey, PersistentDataType.DOUBLE, 0.0);
        double lifeLeech = pdc.getOrDefault(lifeLeechKey, PersistentDataType.DOUBLE, 0.0);
        double defReduction = pdc.getOrDefault(defReductionKey, PersistentDataType.DOUBLE, 0.0);
        double demonDmg = pdc.getOrDefault(demonDmgKey, PersistentDataType.DOUBLE, 0.0);
        double undeadDmg = pdc.getOrDefault(undeadDmgKey, PersistentDataType.DOUBLE, 0.0);
        double slowPct = pdc.getOrDefault(slowPctKey, PersistentDataType.DOUBLE, 0.0);

        // 0.3.6: 최소~최대 범위 피해 (유니크 등). 타격마다 범위 안에서 새로 굴려 고정 추가 피해에 더한다.
        flatPhys += rollDamageRange(pdc, "physical");
        flatFire += rollDamageRange(pdc, "fire");
        flatCold += rollDamageRange(pdc, "cold");
        flatLightning += rollDamageRange(pdc, "lightning");
        flatPoison += rollDamageRange(pdc, "poison");
        flatArcane += rollDamageRange(pdc, "arcane");

        // 적용할 옵션이 하나도 없으면 바닐라 피해 그대로 둔다.
        boolean hasWired =
                attackPct > 0 || flatPhys > 0 || flatFire > 0 || flatCold > 0
                        || flatLightning > 0 || flatPoison > 0 || flatArcane > 0
                        || convertPct > 0 || lifeLeech > 0 || defReduction > 0
                        || critChance > 0 || crushingChance > 0
                        || demonDmg > 0 || undeadDmg > 0 || slowPct > 0;
        if (!hasWired) return;

        double base = event.getDamage(); // 바닐라 기본 피해(예: 다이아몬드 검 7)

        // --- 물리 피해 ---
        // (기본 + 추가 물리) × (1 + 공격력 증가%)
        // 0.2.0과 동일 결과 보존: flatPhys=0 일 때 base × (1 + pct/100)
        double physAfterPct = (base + flatPhys) * (1.0 + attackPct / 100.0);

        // --- 원소 피해 ---
        Map<String, Double> elemental = new LinkedHashMap<>();
        for (String e : elementsConfig()) elemental.put(e, 0.0);

        // 원소 피해 전환: 물리의 convertPct%를 지정 원소로 옮긴다.
        double converted = 0.0;
        if (convertPct > 0 && convertElement != null && elemental.containsKey(convertElement)) {
            converted = physAfterPct * convertPct / 100.0;
            elemental.merge(convertElement, converted, Double::sum);
        }
        double physAfterConvert = physAfterPct - converted;

        // 추가 원소 피해(고정)
        elemental.merge("화염", flatFire, Double::sum);
        elemental.merge("냉기", flatCold, Double::sum);
        elemental.merge("번개", flatLightning, Double::sum);
        elemental.merge("독", flatPoison, Double::sum);
        elemental.merge("비전", flatArcane, Double::sum);

        // --- 치명타 (물리에만) ---
        boolean critted = false;
        double critMult = 1.0;
        if (critChance > 0 && random.nextDouble() * 100.0 < critChance) {
            critMult = ranged ? 1.5 : 2.0; // 근접 2배, 활/석궁 1.5배
            critted = true;
        }
        double physAfterCrit = physAfterConvert * critMult;

        // --- 강타 (물리에만, 대상 현재 체력 비례) ---
        boolean crushed = false;
        double crushingDmg = 0.0;
        if (crushingChance > 0 && event.getEntity() instanceof LivingEntity target) {
            if (random.nextDouble() * 100.0 < crushingChance) {
                double frac = ranged ? 0.15 : 0.25; // 근접 25%, 원거리 15%
                crushingDmg = target.getHealth() * frac;
                crushed = true;
            }
        }
        double totalPhys = physAfterCrit + crushingDmg;

        // --- 악마/언데드 추가 피해 (0.3.2, 물리에만) ---
        // 대상 몹 종류에 따라 물리 피해에 % 추가. 악마와 언데드는 분류가 겹치지 않음.
        boolean demon = false, undead = false;
        double bonusMult = 1.0;
        if (event.getEntity() instanceof LivingEntity target) {
            if (demonDmg > 0 && isDemon(target)) {
                demon = true;
                bonusMult *= 1.0 + demonDmg / 100.0;
            } else if (undeadDmg > 0 && isUndead(target)) {
                undead = true;
                bonusMult *= 1.0 + undeadDmg / 100.0;
            }
        }
        totalPhys *= bonusMult;

        // --- 대상 방어/저항 적용 ---
        // 몹: 종류별 가상 방어력 % (모든 피해에 적용). 플레이어: 갑옷 4칸에서 읽은 방어 통계.
        // defReduction(대상 방어력 감소)은 대상의 방어/저항을 (1 - defReduction/100) 배로 무력화한다.
        // 0.3.0 초기와 달리 ARMOR 수정자를 건드리지 않고, 대상이 가진 방어% 자체를 비율로 깎아
        // 방어력이 없는 일반 몹에게도 defReduction이 의미를 갖도록 한다.
        double targetDefensePct = 0.0;     // 모든 피해에 적용되는 방어%
        double physResistPct = 0.0;        // 물리 추가 저항%
        double flatReduce = 0.0;           // 고정 감소
        Map<String, Double> elemResist = new LinkedHashMap<>();
        for (String e : elementsConfig()) elemResist.put(e, 0.0);

        Entity targetEntity = event.getEntity();
        boolean targetIsPlayer = targetEntity instanceof Player;
        if (targetIsPlayer) {
            DefenseStats vStats = collectPlayerDefense((Player) targetEntity);
            targetDefensePct = vStats.defensePct;
            physResistPct = clampResist(vStats.physResistPct);
            flatReduce = vStats.flatReduce;
            for (String e : elementsConfig()) elemResist.put(e, clampResist(vStats.elemResist(e)));
        } else if (targetEntity instanceof LivingEntity) {
            targetDefensePct = mobDefensePercent(targetEntity);
        }

        // defReduction 적용: 대상 방어/저항을 비율만큼 무력화 (0%→변화 없음, 100%→완전 무시).
        double defReductionFactor = 1.0 - defReduction / 100.0;
        targetDefensePct *= defReductionFactor;
        // 음수 저항(추가 피해)은 방어가 아니므로 감소 대상에서 제외한다 (0.3.4).
        if (physResistPct > 0) physResistPct *= defReductionFactor;
        flatReduce *= defReductionFactor;
        for (Map.Entry<String, Double> e : elemResist.entrySet()) {
            if (e.getValue() > 0) e.setValue(e.getValue() * defReductionFactor);
        }

        // 물리 피해에 방어% + 물리 저항% 적용.
        double physDefPct = targetDefensePct + physResistPct;
        double physFinal = totalPhys * (1.0 - physDefPct / 100.0);
        if (physFinal < 0) physFinal = 0;

        // 원소 피해에 원소 저항% 적용.
        double elementalTotal = 0.0;
        StringBuilder elem = new StringBuilder();
        for (Map.Entry<String, Double> e : elemental.entrySet()) {
            double raw = e.getValue();
            if (raw <= 0) continue;
            double resist = elemResist.getOrDefault(e.getKey(), 0.0);
            double after = raw * (1.0 - resist / 100.0);
            if (after < 0) after = 0;
            elementalTotal += after;
            elem.append(e.getKey()).append('=').append(fmt(raw));
            if (resist != 0) elem.append("(res=").append(fmt(resist)).append("%->").append(fmt(after)).append(") ");
            else elem.append(' ');
        }

        double appliedDamage = physFinal + elementalTotal;
        // 고정 감소 적용.
        double afterFlat = Math.max(0.0, appliedDamage - flatReduce);

        // 최종 BASE 피해 적용
        event.setDamage(afterFlat);

        // --- 생명력 흡수 ---
        // 적용 피해(방어/저항 적용 후, 고정 감소 전) 기준. 다음 틱에 회복시켜 이벤트 중 상태 간섭을 피한다.
        double healAmount = lifeLeech > 0 ? appliedDamage * lifeLeech / 100.0 : 0.0;
        if (healAmount > 0) {
            final double heal = healAmount;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline() || player.isDead()) return;
                double max = player.getAttribute(VersionCompat.MAX_HEALTH).getValue();
                double next = Math.min(max, player.getHealth() + heal);
                player.setHealth(next);
            });
        }

        // --- 대상 둔화 (0.3.2) ---
        // slowPct% 확률로 대상에게 Slowness(amplifier 1 = 레벨 2, 5초=100틱) 부여.
        boolean slowed = false;
        if (slowPct > 0 && event.getEntity() instanceof LivingEntity target) {
            if (random.nextDouble() * 100.0 < slowPct) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1));
                slowed = true;
            }
        }

        // --- 콘솔 로그 (항목별 분석) ---
        getLogger().info(String.format(Locale.ROOT,
                "[%s -> %s] ranged=%s via=%s | base=%.1f +flatPhys=%.1f xAtkPct=%.0f%% -> phys=%.2f"
                        + " | convert=%.0f%%->%s(conv=%.2f) physAfterConv=%.2f"
                        + " | crit=%s(chance=%.0f%% mult=%.1f) physAfterCrit=%.2f"
                        + " | crushing=%s(chance=%.0f%% dmg=%.2f) totalPhys=%.2f"
                        + " | demon=%s(%.0f%%) undead=%s(%.0f%%) bonus=%.2f"
                        + " | elemental: %s(sum=%.2f)"
                        + " | defReduction=%.0f%% targetDef=%.1f%% physRes=%.1f%% elemRes=[%s] flat=%.1f"
                        + " | physFinal=%.2f appliedPreFlat=%.2f afterFlat=%.2f"
                        + " | leech=%.1f%% heal=%.2f slow=%s(%.0f%%)",
                player.getName(), event.getEntity().getName(), ranged, via,
                base, flatPhys, attackPct, physAfterPct,
                convertPct, convertElement == null ? "-" : convertElement, converted, physAfterConvert,
                critted, critChance, critMult, physAfterCrit,
                crushed, crushingChance, crushingDmg, totalPhys,
                demon, demonDmg, undead, undeadDmg, bonusMult,
                elem.toString().trim(), elementalTotal,
                defReduction, targetDefensePct, physResistPct, elemResistSummary(elemResist), flatReduce,
                physFinal, appliedDamage, afterFlat,
                lifeLeech, healAmount, slowed, slowPct));
    }

    // ===================================================================
    //  전투: 투사체(활/석궁) 옵션 보존 (0.3.4)
    //  발사하는 순간 무기의 옵션 PDC를 화살 엔티티 PDC로 통째로 복사한다.
    //  명중 시점에는 onPlayerAttack이 화살의 PDC를 읽어 같은 계산을 한다.
    //  (무기를 바꾸거나 버려도 이미 날아간 화살의 옵션은 유지된다.)
    // ===================================================================

    /** 활/석궁 발사 이벤트: 정확히 어떤 무기로 쐈는지 알 수 있다. */
    @EventHandler
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        ItemStack bow = event.getBow();
        if (bow == null || !isRangedWeapon(bow.getType()) || !bow.hasItemMeta()) return;
        Entity projectile = event.getProjectile();
        if (!(projectile instanceof AbstractArrow) || projectile instanceof Trident) return;
        copyOptionsToProjectile(bow, projectile);
    }

    /**
     * 투사체 생성 이벤트: 다발사격(멀티샷)처럼 발사 이벤트가 한 번만 오는 경우 추가 화살도
     * 옵션을 갖도록 보완한다. 이미 복사된 화살에는 같은 값을 다시 덮어쓸 뿐이다.
     */
    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof AbstractArrow arrow)) return;
        if (arrow instanceof Trident) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;

        ItemStack weapon = findHeldRangedWeapon(shooter);
        if (weapon == null) return;
        copyOptionsToProjectile(weapon, arrow);
    }

    /**
     * 플레이어가 쏠 때 쓰는 활/석궁을 찾는다.
     * 주 손에 활/석궁이 있으면 그것만 본다(보조 손 무기를 잘못 복사하지 않기 위해).
     * 주 손이 활/석궁이 아니면 보조 손을 본다. RPG 아이템이 아니면 null.
     */
    private ItemStack findHeldRangedWeapon(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack chosen;
        if (isRangedWeapon(main.getType())) {
            chosen = main;
        } else {
            ItemStack off = player.getInventory().getItemInOffHand();
            if (!isRangedWeapon(off.getType())) return null;
            chosen = off;
        }
        if (!chosen.hasItemMeta()) return null;
        if (!chosen.getItemMeta().getPersistentDataContainer().has(rarityKey, PersistentDataType.STRING)) {
            return null;
        }
        return chosen;
    }

    /**
     * 무기 PDC의 모든 항목(DOUBLE/STRING)을 투사체 PDC로 복사한다.
     * 키 목록을 따로 관리하지 않으므로 앞으로 옵션이 추가되어도 자동으로 따라온다.
     */
    private void copyOptionsToProjectile(ItemStack weapon, Entity projectile) {
        if (weapon == null || !weapon.hasItemMeta()) return;
        PersistentDataContainer src = weapon.getItemMeta().getPersistentDataContainer();
        if (!src.has(rarityKey, PersistentDataType.STRING)) return; // RPG 아이템이 아니면 복사하지 않음
        PersistentDataContainer dst = projectile.getPersistentDataContainer();
        for (NamespacedKey key : src.getKeys()) {
            if (src.has(key, PersistentDataType.DOUBLE)) {
                dst.set(key, PersistentDataType.DOUBLE, src.get(key, PersistentDataType.DOUBLE));
            } else if (src.has(key, PersistentDataType.STRING)) {
                dst.set(key, PersistentDataType.STRING, src.get(key, PersistentDataType.STRING));
            }
        }
    }

    /**
     * 저항 상한/하한 적용 (0.3.4).
     * 기본: 최대 75%, 최소 -100%. config의 resist.max / resist.min으로 조정 가능.
     * 음수 저항은 피해를 더 받는다(저항 -50% → 해당 피해 1.5배).
     */
    private double clampResist(double value) {
        double max = getConfig().getDouble("resist.max", 75.0);
        double min = getConfig().getDouble("resist.min", -100.0);
        return Math.max(min, Math.min(max, value));
    }

    // ===================================================================
    //  전투: 피해 받을 때(방어/저항 적용, 피해자 기준)
    // ===================================================================

    /**
     * 플레이어가 피해를 받을 때 방어/저항 옵션을 적용한다.
     * - RPG 무기를 든 플레이어에게 맞은 경우는 onPlayerAttack에서 이미 대상 방어를 적용했으므로 건너뛴다.
     * - 그 외(몹 근접, 환경 피해, 바닐라 무기 PvP)에는 갑옷에서 읽은 방어 통계를 적용한다.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDefended(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        // RPG 무기를 든 플레이어가 때린 경우: 이미 공격 핸들러에서 방어 적용 완료.
        if (event instanceof EntityDamageByEntityEvent ede
                && ede.getDamager() instanceof Player damager) {
            ItemStack weapon = damager.getInventory().getItemInMainHand();
            if (weapon.hasItemMeta()
                    && weapon.getItemMeta().getPersistentDataContainer()
                            .has(rarityKey, PersistentDataType.STRING)) {
                return;
            }
        }

        // RPG 활/석궁 화살에 맞은 경우도 공격 핸들러에서 이미 방어를 적용했으므로 건너뛴다.
        if (event instanceof EntityDamageByEntityEvent edeArrow
                && edeArrow.getDamager() instanceof AbstractArrow arrow
                && !(arrow instanceof Trident)
                && arrow.getShooter() instanceof Player
                && arrow.getPersistentDataContainer().has(rarityKey, PersistentDataType.STRING)) {
            return;
        }

        DefenseStats stats = collectPlayerDefense(victim);
        if (!stats.hasAny()) return;

        double base = event.getDamage();
        DamageCause cause = event.getCause();
        String causeElement = elementForCause(cause);

        // 원소 피해면 해당 원소 저항, 아니면 물리 저항 적용.
        double elemResistPct = causeElement == null ? 0.0 : clampResist(stats.elemResist(causeElement));
        double physResistPct = causeElement == null ? clampResist(stats.physResistPct) : 0.0;

        double reduced = base
                * (1.0 - stats.defensePct / 100.0)
                * (1.0 - elemResistPct / 100.0)
                * (1.0 - physResistPct / 100.0);
        double afterFlat = Math.max(0.0, reduced - stats.flatReduce);

        event.setDamage(afterFlat);

        getLogger().info(String.format(Locale.ROOT,
                "[%s 피해받음 cause=%s elem=%s] base=%.2f def=%.0f%% elemRes=%.0f%% physRes=%.0f%% flat=%.1f"
                        + " -> afterFlat=%.2f",
                victim.getName(), cause, causeElement == null ? "-" : causeElement,
                base, stats.defensePct, elemResistPct, physResistPct, stats.flatReduce, afterFlat));
    }

    /** DamageCause → 원소 매핑. null이면 물리/기타(원소 저항 없음). */
    private String elementForCause(DamageCause cause) {
        return switch (cause) {
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, MELTING -> "화염";
            case FREEZE -> "냉기";
            case LIGHTNING -> "번개";
            case POISON -> "독";
            case MAGIC -> "비전";
            default -> null;
        };
    }

    /**
     * 몹 종류별 가상 방어력 %.
     * 마인크래프트 기본 몹은 방어력(ARMOR)이 거의 없어 "대상 방어력 감소" 옵션이 체감되지 않으므로,
     * 디아블로 느낌의 종류별 방어%를 부여한다. 일반 몹은 5%, 보스급은 25~50%.
     */
    private double mobDefensePercent(Entity entity) {
        // 0.3.7: 샌드백은 소환할 때 정한 방어%를 쓴다.
        Double dummyDef = entity.getPersistentDataContainer().get(dummyDefenseKey, PersistentDataType.DOUBLE);
        if (dummyDef != null) return dummyDef;
        String name = entity.getType().name();
        return switch (name) {
            case "WARDEN" -> 50.0;
            case "ENDER_DRAGON" -> 40.0;
            case "RAVAGER" -> 35.0;
            case "IRON_GOLEM" -> 30.0;
            case "WITHER" -> 25.0;
            case "ENDERMAN", "PIGLIN_BRUTE" -> 20.0;
            case "HOGLIN", "ZOGLIN" -> 15.0;
            case "BLAZE", "SPIDER", "CAVE_SPIDER", "GHAST" -> 10.0;
            default -> 5.0;
        };
    }

    /** 플레이어 갑옷 4칸(머리/가슴/바지/신발)에서 방어 통계를 합산한다. */
    private DefenseStats collectPlayerDefense(Player player) {
        DefenseStats s = new DefenseStats();
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor == null || armor.getType().isAir() || !armor.hasItemMeta()) continue;
            PersistentDataContainer pdc = armor.getItemMeta().getPersistentDataContainer();
            if (!pdc.has(rarityKey, PersistentDataType.STRING)) continue;
            s.defensePct += pdc.getOrDefault(defensePctKey, PersistentDataType.DOUBLE, 0.0);
            s.physResistPct += pdc.getOrDefault(physResistKey, PersistentDataType.DOUBLE, 0.0);
            s.fireResistPct += pdc.getOrDefault(fireResistKey, PersistentDataType.DOUBLE, 0.0);
            s.coldResistPct += pdc.getOrDefault(coldResistKey, PersistentDataType.DOUBLE, 0.0);
            s.lightningResistPct += pdc.getOrDefault(lightningResistKey, PersistentDataType.DOUBLE, 0.0);
            s.poisonResistPct += pdc.getOrDefault(poisonResistKey, PersistentDataType.DOUBLE, 0.0);
            s.arcaneResistPct += pdc.getOrDefault(arcaneResistKey, PersistentDataType.DOUBLE, 0.0);
            s.allElemResistPct += pdc.getOrDefault(allElemResistKey, PersistentDataType.DOUBLE, 0.0);
            s.flatReduce += pdc.getOrDefault(flatDmgReduceKey, PersistentDataType.DOUBLE, 0.0);
        }
        return s;
    }

    /** 원소 저항 맵을 로그용 요약 문자열로 만든다. */
    private String elemResistSummary(Map<String, Double> elemResist) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : elemResist.entrySet()) {
            if (e.getValue() != 0) sb.append(e.getKey()).append('=').append(fmt(e.getValue())).append("% ");
        }
        return sb.toString().trim();
    }

    /** 플레이어 방어 통계(갑옷 4칸 합산). */
    private static final class DefenseStats {
        double defensePct;       // 방어력 증가 % (모든 피해 감소)
        double physResistPct;    // 물리 저항 %
        double fireResistPct;
        double coldResistPct;
        double lightningResistPct;
        double poisonResistPct;
        double arcaneResistPct;
        double allElemResistPct; // 모든 원소 저항 %
        double flatReduce;       // 피해 감소 (고정)

        boolean hasAny() {
            // 0.3.5: 음수 저항(추가 피해)도 "방어 통계 있음"으로 본다. (!= 0)
            return defensePct != 0 || physResistPct != 0 || fireResistPct != 0
                    || coldResistPct != 0 || lightningResistPct != 0 || poisonResistPct != 0
                    || arcaneResistPct != 0 || allElemResistPct != 0 || flatReduce != 0;
        }

        /** 해당 원소의 총 저항% (모든 원소 저항 + 개별 원소 저항). */
        double elemResist(String elem) {
            double r = allElemResistPct;
            return switch (elem) {
                case "화염" -> r + fireResistPct;
                case "냉기" -> r + coldResistPct;
                case "번개" -> r + lightningResistPct;
                case "독" -> r + poisonResistPct;
                case "비전" -> r + arcaneResistPct;
                default -> r;
            };
        }
    }

    /** 활/석궁을 원거리로 분류. 그 외 무기는 근접. */
    private boolean isRangedWeapon(Material material) {
        return material == Material.BOW || material == Material.CROSSBOW;
    }

    /**
     * 언데드 판정 (0.3.2). 마인크래프트의 언데드 계열 몹.
     * 좀비/스켈레톤 계열, 팬텀, 위더 등.
     */
    private boolean isUndead(LivingEntity entity) {
        return switch (entity.getType()) {
            case ZOMBIE, HUSK, DROWNED, ZOMBIE_VILLAGER, ZOMBIFIED_PIGLIN,
                 SKELETON, STRAY, BOGGED, WITHER_SKELETON,
                 PHANTOM, WITHER -> true;
            default -> false;
        };
    }

    /**
     * 악마 판정 (0.3.2). 디아블로 느낌의 악마 계열 몹.
     * 블레이즈/가스트/마그마 큐브/워든 등 (엔더 계열은 제외).
     */
    private boolean isDemon(LivingEntity entity) {
        return switch (entity.getType()) {
            case BLAZE, GHAST, MAGMA_CUBE, WARDEN -> true;
            default -> false;
        };
    }

    /**
     * PDC에 저장된 능력치 수치를 ItemMeta에 AttributeModifier로 추가한다 (0.3.1).
     * - 최대 생명력: ADD_NUMBER로 고정 수치 추가. 예: +20 → 체력 20칸(10하트) 증가.
     * - 공격 속도 증가 %: 기본 공속 4.0 기준 ADD_NUMBER. 예: 10% → +0.4 → 검 공속 1.6→2.0.
     *   무기의 기본 ATTACK_SPEED modifier(-2.4)는 addAttributeModifier가 보존하므로
     *   새 modifier가 추가로 더해져 공속이 빨라진다.
     * - 이동 속도 증가 %: MULTIPLY로 비율 추가. 예: 10% → ×1.10 → 걷기 10% 빠름.
     * 마인크래프트가 장비 입/벗을 때 자동으로 적용·복구한다 (별도 리스너 불필요).
     */
    private void applyAttributeModifiers(ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        double maxHealth = pdc.getOrDefault(maxHealthKey, PersistentDataType.DOUBLE, 0.0);
        double atkSpeedPct = pdc.getOrDefault(attackSpeedKey, PersistentDataType.DOUBLE, 0.0);
        double moveSpeedPct = pdc.getOrDefault(moveSpeedKey, PersistentDataType.DOUBLE, 0.0);

        if (maxHealth > 0) {
            AttributeModifier mod = new AttributeModifier(
                    new NamespacedKey(this, "maxhealth_mod"),
                    maxHealth,
                    AttributeModifier.Operation.ADD_NUMBER);
            meta.addAttributeModifier(VersionCompat.MAX_HEALTH, mod);
        }
        if (atkSpeedPct > 0) {
            // 기본 공속 4.0 × pct/100 을 ADD_NUMBER로 추가.
            double addAmount = 4.0 * atkSpeedPct / 100.0;
            AttributeModifier mod = new AttributeModifier(
                    new NamespacedKey(this, "atkspeed_mod"),
                    addAmount,
                    AttributeModifier.Operation.ADD_NUMBER);
            meta.addAttributeModifier(VersionCompat.ATTACK_SPEED, mod);
        }
        if (moveSpeedPct > 0) {
            // MULTIPLY_SCALAR_1: amount를 비율로 해석. 10% → amount 0.10 → ×1.10.
            AttributeModifier mod = new AttributeModifier(
                    new NamespacedKey(this, "movespeed_mod"),
                    moveSpeedPct / 100.0,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1);
            meta.addAttributeModifier(VersionCompat.MOVEMENT_SPEED, mod);
        }
    }

    private String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ===================================================================
    //  명령어: /rpgitem give <플레이어> <등급> <아이템>
    // ===================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("rpgitem")) return handleRpgItem(sender, args);
        if (name.equals("rpgtest")) return handleRpgTest(sender, args);
        if (name.equals("rpgdummy")) return handleRpgDummy(sender, args);
        return false;
    }

    // ===================================================================
    //  명령어: /rpgitem give <플레이어> <등급> <아이템>
    // ===================================================================

    private boolean handleRpgItem(CommandSender sender, String[] args) {
        if (args.length != 4 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(ChatColor.YELLOW + "사용법: /rpgitem give <플레이어> "
                    + "<normal|magic|rare|unique|set|runeword> <아이템|random_weapon|유니크ID>");
            return true;
        }

        Player target = getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "플레이어를 찾을 수 없습니다.");
            return true;
        }

        String rarity = args[2].toLowerCase(Locale.ROOT);
        if (!ALL_RARITIES.contains(rarity)) {
            sender.sendMessage(ChatColor.RED + "지원 등급: normal, magic, rare, unique, set, runeword");
            return true;
        }

        if (rarity.equals("unique")) {
            String uniqueId = args[3].toLowerCase(Locale.ROOT);
            ItemStack uniqueItem = createUniqueItem(uniqueId);
            if (uniqueItem == null) {
                sender.sendMessage(ChatColor.RED + "유니크 ID를 찾을 수 없습니다. 등록된 ID: " + uniqueIds());
                return true;
            }
            target.getInventory().addItem(uniqueItem).values().forEach(left ->
                    target.getWorld().dropItemNaturally(target.getLocation(), left));
            sender.sendMessage(ChatColor.GREEN + target.getName() + "에게 유니크 아이템(" + uniqueId + ")을 지급했습니다.");
            return true;
        }

        if (UNIMPLEMENTED_RARITIES.contains(rarity)) {
            sender.sendMessage(ChatColor.YELLOW + rarity + " 등급은 0.3.0에서 아직 구현되지 않았습니다 (미검증).");
            return true;
        }

        Material material;
        if (args[3].equalsIgnoreCase("random_weapon")) {
            // 무기 풀에서 무작위로 하나 선택. 옵션은 createItem이 희귀도에 맞게 랜덤 부착.
            material = RANDOM_WEAPONS.get(random.nextInt(RANDOM_WEAPONS.size()));
        } else {
            material = Material.matchMaterial(args[3]);
            if (material == null || !material.isItem()) {
                sender.sendMessage(ChatColor.RED + "올바른 아이템 이름을 입력하세요. 예: DIAMOND_SWORD 또는 random_weapon");
                return true;
            }
        }

        ItemStack item = createItem(material, rarity);
        if (item == null) {
            sender.sendMessage(ChatColor.RED + "아이템 생성에 실패했습니다.");
            return true;
        }
        target.getInventory().addItem(item).values().forEach(left ->
                target.getWorld().dropItemNaturally(target.getLocation(), left));

        sender.sendMessage(ChatColor.GREEN + target.getName() + "에게 "
                + getConfig().getString("rarities." + rarity + ".display", rarity) + " 아이템을 지급했습니다.");
        return true;
    }

    // ===================================================================
    //  명령어: /rpgtest <플레이어> <crit|crush|leech|defred>
    //  특정 옵션 하나만 확정 부착한 테스트용 다이아몬드 검을 지급한다.
    //  다른 옵션은 전혀 붙이지 않아 콘솔 로그가 깔끔하게 해당 옵션만 보이도록 한다.
    // ===================================================================

    private boolean handleRpgTest(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(ChatColor.YELLOW + "사용법: /rpgtest <플레이어> <종류>");
            sender.sendMessage(ChatColor.YELLOW + "  -- 공격(검) --");
            sender.sendMessage(ChatColor.YELLOW + "  crit   = 치명타 100% (매 타격 2배 피해)");
            sender.sendMessage(ChatColor.YELLOW + "  crush  = 강타 100% (대상 현재 체력의 25% 추가 피해)");
            sender.sendMessage(ChatColor.YELLOW + "  leech  = 생명력 흡수 50% (피해의 50% 회복)");
            sender.sendMessage(ChatColor.YELLOW + "  defred = 대상 방어력 감소 100% (대상 방어/저항 완전 무시)");
            sender.sendMessage(ChatColor.YELLOW + "  -- 방어(갑옷:가슴) --");
            sender.sendMessage(ChatColor.YELLOW + "  armor  = 방어력 증가 50% (모든 피해 50% 감소)");
            sender.sendMessage(ChatColor.YELLOW + "  physres = 물리 저항 50%");
            sender.sendMessage(ChatColor.YELLOW + "  fireres = 화염 저항 75%");
            sender.sendMessage(ChatColor.YELLOW + "  allres  = 모든 원소 저항 50%");
            sender.sendMessage(ChatColor.YELLOW + "  dmgred  = 피해 감소 5 (고정)");
            sender.sendMessage(ChatColor.YELLOW + "  -- 능력치(0.3.1) --");
            sender.sendMessage(ChatColor.YELLOW + "  health   = 최대 생명력 +20 (체력 10하트 추가, 갑옷:가슴)");
            sender.sendMessage(ChatColor.YELLOW + "  atkspeed = 공격 속도 +50% (검, 공속 1.6->2.4)");
            sender.sendMessage(ChatColor.YELLOW + "  movespeed = 이동 속도 +50% (걷기 1.5배, 갑옷:가슴)");
            sender.sendMessage(ChatColor.YELLOW + "  -- 대상 특화(0.3.2) --");
            sender.sendMessage(ChatColor.YELLOW + "  demon  = 악마 추가 피해 +50% (블레이즈/가스트/워든 등에게)");
            sender.sendMessage(ChatColor.YELLOW + "  undead = 언데드 추가 피해 +50% (좀비/스켈레톤/팬텀 등에게)");
            sender.sendMessage(ChatColor.YELLOW + "  slow   = 대상 둔화 100% (때리면 5초간 느려짐)");
            sender.sendMessage(ChatColor.YELLOW + "  -- 투사체/저항 상한(0.3.4) --");
            sender.sendMessage(ChatColor.YELLOW + "  bow      = 활 + 치명타 100% (화살에도 옵션이 따라가는지)");
            sender.sendMessage(ChatColor.YELLOW + "  crossbow = 석궁 + 강타 100%");
            sender.sendMessage(ChatColor.YELLOW + "  bowmix   = 활 + 치명타 100% + 화염 +10 (물리/원소 분리 확인)");
            sender.sendMessage(ChatColor.YELLOW + "  overres  = 갑옷(가슴) 화염 저항 120% (75%로 잘리는지)");
            sender.sendMessage(ChatColor.YELLOW + "  negres   = 갑옷(가슴) 화염 저항 -50% (화염 피해 1.5배)");
            return true;
        }

        Player target = getServer().getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "플레이어를 찾을 수 없습니다.");
            return true;
        }

        String type = args[1].toLowerCase(Locale.ROOT);
        ItemStack item = createTestItem(type);
        if (item == null) {
            sender.sendMessage(ChatColor.RED + "테스트 종류: crit, crush, leech, defred, armor, physres, fireres, allres, dmgred, health, atkspeed, movespeed, demon, undead, slow, bow, crossbow, bowmix, overres, negres");
            return true;
        }

        target.getInventory().addItem(item).values().forEach(left ->
                target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(ChatColor.GREEN + target.getName() + "에게 테스트 아이템(" + type + ")을 지급했습니다.");
        return true;
    }

    /**
     * 테스트용 아이템 생성.
     * - 공격 옵션(crit/crush/leech/defred): 다이아몬드 검에 해당 옵션 하나만 고정 부착.
     * - 방어 옵션(armor/physres/fireres/allres/dmgred): 다이아몬드 갑옷(가슴)에 해당 옵션 하나만 고정 부착.
     * 다른 옵션은 전혀 넣지 않아 콘솔 로그가 깔끔하게 해당 옵션만 보이도록 한다.
     * rarityKey를 "unique"로 설정하여 전투 파이프라인이 개입하도록 한다.
     */
    private ItemStack createTestItem(String type) {
        Material material;
        String label;
        switch (type) {
            // --- 공격 옵션 (검) ---
            case "crit" -> {
                material = Material.DIAMOND_SWORD;
                label = "치명타 확률 +100% (매 타격 2배)";
            }
            case "crush" -> {
                material = Material.DIAMOND_SWORD;
                label = "강타 확률 +100% (현재 체력 25% 추가)";
            }
            case "leech" -> {
                material = Material.DIAMOND_SWORD;
                label = "생명력 흡수 +50% (피해의 50% 회복)";
            }
            case "defred" -> {
                material = Material.DIAMOND_SWORD;
                label = "대상 방어력 감소 +100% (대상 방어/저항 완전 무시)";
            }
            // --- 방어 옵션 (갑옷: 가슴) ---
            case "armor" -> {
                material = Material.DIAMOND_CHESTPLATE;
                label = "방어력 증가 +50% (모든 피해 50% 감소)";
            }
            case "physres" -> {
                material = Material.DIAMOND_CHESTPLATE;
                label = "물리 저항 +50% (물리 피해 추가 50% 감소)";
            }
            case "fireres" -> {
                material = Material.DIAMOND_CHESTPLATE;
                label = "화염 저항 +75% (화염 피해 75% 감소)";
            }
            case "allres" -> {
                material = Material.DIAMOND_CHESTPLATE;
                label = "모든 원소 저항 +50% (원소 피해 50% 감소)";
            }
            case "dmgred" -> {
                material = Material.DIAMOND_CHESTPLATE;
                label = "피해 감소 +5 (고정 5 감소)";
            }
            // --- 능력치 옵션 (0.3.1: AttributeModifier로 자동 적용) ---
            case "health" -> {
                material = Material.DIAMOND_CHESTPLATE;
                label = "최대 생명력 +20 (체력 10하트 추가)";
            }
            case "atkspeed" -> {
                material = Material.DIAMOND_SWORD;
                label = "공격 속도 +50% (공속 1.6->2.4)";
            }
            case "movespeed" -> {
                material = Material.DIAMOND_CHESTPLATE;
                label = "이동 속도 +50% (걷기 1.5배)";
            }
            // --- 대상 특화 옵션 (0.3.2) ---
            case "demon" -> {
                material = Material.DIAMOND_SWORD;
                label = "악마 추가 피해 +50% (블레이즈/가스트/워든 등에게)";
            }
            case "undead" -> {
                material = Material.DIAMOND_SWORD;
                label = "언데드 추가 피해 +50% (좀비/스켈레톤/팬텀 등에게)";
            }
            case "slow" -> {
                material = Material.DIAMOND_SWORD;
                label = "대상 둔화 100% (때리면 5초간 느려짐)";
            }
            // --- 투사체/저항 상한 테스트 (0.3.4) ---
            case "bow" -> {
                material = Material.BOW;
                label = "치명타 확률 +100% (활: 물리 피해 1.5배)";
            }
            case "crossbow" -> {
                material = Material.CROSSBOW;
                label = "강타 확률 +100% (석궁: 대상 현재 체력의 15% 추가)";
            }
            case "bowmix" -> {
                material = Material.BOW;
                label = "치명타 +100% & 화염 피해 +10 (치명타는 물리에만, 화염은 그대로)";
            }
            case "overres" -> {
                material = Material.DIAMOND_CHESTPLATE;
                label = "화염 저항 +120% (상한 75%로 잘려야 함)";
            }
            case "negres" -> {
                material = Material.DIAMOND_CHESTPLATE;
                label = "화염 저항 -50% (화염 피해 1.5배)";
            }
            default -> {
                return null; // 알 수 없는 종류
            }
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(rarityKey, PersistentDataType.STRING, "unique");
        switch (type) {
            case "crit" -> meta.getPersistentDataContainer().set(critChanceKey, PersistentDataType.DOUBLE, 100.0);
            case "crush" -> meta.getPersistentDataContainer().set(crushingChanceKey, PersistentDataType.DOUBLE, 100.0);
            case "leech" -> meta.getPersistentDataContainer().set(lifeLeechKey, PersistentDataType.DOUBLE, 50.0);
            case "defred" -> meta.getPersistentDataContainer().set(defReductionKey, PersistentDataType.DOUBLE, 100.0);
            case "armor" -> meta.getPersistentDataContainer().set(defensePctKey, PersistentDataType.DOUBLE, 50.0);
            case "physres" -> meta.getPersistentDataContainer().set(physResistKey, PersistentDataType.DOUBLE, 50.0);
            case "fireres" -> meta.getPersistentDataContainer().set(fireResistKey, PersistentDataType.DOUBLE, 75.0);
            case "allres" -> meta.getPersistentDataContainer().set(allElemResistKey, PersistentDataType.DOUBLE, 50.0);
            case "dmgred" -> meta.getPersistentDataContainer().set(flatDmgReduceKey, PersistentDataType.DOUBLE, 5.0);
            case "health" -> meta.getPersistentDataContainer().set(maxHealthKey, PersistentDataType.DOUBLE, 20.0);
            case "atkspeed" -> meta.getPersistentDataContainer().set(attackSpeedKey, PersistentDataType.DOUBLE, 50.0);
            case "movespeed" -> meta.getPersistentDataContainer().set(moveSpeedKey, PersistentDataType.DOUBLE, 50.0);
            case "demon" -> meta.getPersistentDataContainer().set(demonDmgKey, PersistentDataType.DOUBLE, 50.0);
            case "undead" -> meta.getPersistentDataContainer().set(undeadDmgKey, PersistentDataType.DOUBLE, 50.0);
            case "slow" -> meta.getPersistentDataContainer().set(slowPctKey, PersistentDataType.DOUBLE, 100.0);
            case "bow" -> meta.getPersistentDataContainer().set(critChanceKey, PersistentDataType.DOUBLE, 100.0);
            case "crossbow" -> meta.getPersistentDataContainer().set(crushingChanceKey, PersistentDataType.DOUBLE, 100.0);
            case "bowmix" -> {
                meta.getPersistentDataContainer().set(critChanceKey, PersistentDataType.DOUBLE, 100.0);
                meta.getPersistentDataContainer().set(flatFireKey, PersistentDataType.DOUBLE, 10.0);
            }
            case "overres" -> meta.getPersistentDataContainer().set(fireResistKey, PersistentDataType.DOUBLE, 120.0);
            case "negres" -> meta.getPersistentDataContainer().set(fireResistKey, PersistentDataType.DOUBLE, -50.0);
            default -> { /* 도달 불가 */ }
        }

        // 능력치 옵션(health/atkspeed/movespeed)은 AttributeModifier로 ItemMeta에 추가.
        // 공격/방어 테스트 아이템에는 해당 PDC 키가 없으므로 아무 modifier도 붙지 않는다.
        applyAttributeModifiers(meta);

        meta.setDisplayName(ChatColor.GOLD + "[테스트] " + prettyName(material));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "등급: 테스트(옵션 1개 확정)");
        lore.add(ChatColor.GRAY + label);
        lore.add(ChatColor.DARK_GRAY + "DiabloRPG 0.3.8 (test)");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ===================================================================
    //  아이템 생성
    // ===================================================================

    private ItemStack createItem(Material material, String rarity) {
        if (!ROLLABLE_RARITIES.contains(rarity)) return null;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        String rarityName = getConfig().getString("rarities." + rarity + ".display", rarity);
        meta.setDisplayName(rarityColor(rarity) + rarityName + " " + prettyName(material));
        meta.getPersistentDataContainer().set(rarityKey, PersistentDataType.STRING, rarity);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "등급: " + rarityName);

        ConfigurationSection raritySection = getConfig().getConfigurationSection("rarities." + rarity);
        int min = raritySection == null ? 0 : raritySection.getInt("min-options", 0);
        int max = raritySection == null ? 0 : raritySection.getInt("max-options", 0);
        int count = min + (max > min ? random.nextInt(max - min + 1) : 0);

        ConfigurationSection options = getConfig().getConfigurationSection("options");
        if (options != null && count > 0) {
            // 이 희귀도에 롤 가능한 옵션만 풀에 넣는다 (갑옷·무기 구분 없이 전체 풀).
            List<String> rollable = new ArrayList<>();
            for (String name : options.getKeys(false)) {
                ConfigurationSection opt = options.getConfigurationSection(name);
                if (opt == null) continue;
                if (rangeForRarity(opt, rarity) != null) rollable.add(name);
            }
            Collections.shuffle(rollable, random);
            int pick = Math.min(count, rollable.size());
            for (int i = 0; i < pick; i++) {
                String name = rollable.get(i);
                ConfigurationSection opt = options.getConfigurationSection(name);
                int[] range = rangeForRarity(opt, rarity);
                int low = range[0];
                int high = range[1];
                int value = low + (high > low ? random.nextInt(high - low + 1) : 0);
                String suffix = opt.getString("suffix", "");

                // 원소 피해 전환: 대상 원소를 무작위로 정해 PDC에 저장.
                String extra = "";
                if (name.equals("원소 피해 전환")) {
                    String elem = randomElement();
                    extra = " (" + elem + ")";
                    meta.getPersistentDataContainer().set(convertElementKey, PersistentDataType.STRING, elem);
                }

                lore.add(ChatColor.GRAY + name + " +" + value + suffix + extra);
                applyOptionToPdc(meta, name, value);
            }
        }

        // ===== 능력치 AttributeModifier 적용 (0.3.1) =====
        // PDC에 저장된 최대 생명력/공속/이속 수치를 읽어 ItemMeta에 AttributeModifier로 추가.
        // 마인크래프트가 장비 입/벗을 때 자동으로 적용·복구한다.
        applyAttributeModifiers(meta);

        lore.add(ChatColor.DARK_GRAY + "DiabloRPG 0.3.8");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 옵션의 해당 희귀도 구간을 반환.
     *  - 새 config: opt.{rarity}.{min,max}
     *  - 구 config(0.2.0) 호환: opt.min / opt.max (flat) — magic·rare 모두에 사용.
     *  - 해당 희귀도에 구간이 없으면 null (이 희귀도에는 롤 불가).
     */
    private int[] rangeForRarity(ConfigurationSection opt, String rarity) {
        ConfigurationSection sub = opt.getConfigurationSection(rarity);
        if (sub != null) {
            int lo = sub.getInt("min", 0);
            int hi = sub.getInt("max", lo);
            return new int[]{lo, hi};
        }
        if (opt.isSet("min") && opt.isSet("max")) {
            return new int[]{opt.getInt("min"), opt.getInt("max")};
        }
        return null;
    }

    /** 옵션 이름 → PDC 키 매핑. 전투에 연결된 옵션만 저장하고, 나머지는 로어 표시용으로 무시한다. */
    private void applyOptionToPdc(ItemMeta meta, String name, int value) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        double v = value;
        switch (name) {
            case "공격력 증가" -> pdc.set(attackBonusKey, PersistentDataType.DOUBLE, v);
            case "물리 피해 추가" -> pdc.set(flatPhysicalKey, PersistentDataType.DOUBLE, v);
            case "화염 피해 추가" -> pdc.set(flatFireKey, PersistentDataType.DOUBLE, v);
            case "냉기 피해 추가" -> pdc.set(flatColdKey, PersistentDataType.DOUBLE, v);
            case "번개 피해 추가" -> pdc.set(flatLightningKey, PersistentDataType.DOUBLE, v);
            case "독 피해 추가" -> pdc.set(flatPoisonKey, PersistentDataType.DOUBLE, v);
            case "비전 피해 추가" -> pdc.set(flatArcaneKey, PersistentDataType.DOUBLE, v);
            case "원소 피해 전환" -> pdc.set(convertPctKey, PersistentDataType.DOUBLE, v);
            case "생명력 흡수" -> pdc.set(lifeLeechKey, PersistentDataType.DOUBLE, v);
            case "대상 방어력 감소" -> pdc.set(defReductionKey, PersistentDataType.DOUBLE, v);
            case "치명타 확률" -> pdc.set(critChanceKey, PersistentDataType.DOUBLE, v);
            case "강타 확률" -> pdc.set(crushingChanceKey, PersistentDataType.DOUBLE, v);
            case "방어력 증가" -> pdc.set(defensePctKey, PersistentDataType.DOUBLE, v);
            case "물리 저항" -> pdc.set(physResistKey, PersistentDataType.DOUBLE, v);
            case "화염 저항" -> pdc.set(fireResistKey, PersistentDataType.DOUBLE, v);
            case "냉기 저항" -> pdc.set(coldResistKey, PersistentDataType.DOUBLE, v);
            case "번개 저항" -> pdc.set(lightningResistKey, PersistentDataType.DOUBLE, v);
            case "독 저항" -> pdc.set(poisonResistKey, PersistentDataType.DOUBLE, v);
            case "비전 저항" -> pdc.set(arcaneResistKey, PersistentDataType.DOUBLE, v);
            case "모든 원소 저항" -> pdc.set(allElemResistKey, PersistentDataType.DOUBLE, v);
            case "피해 감소" -> pdc.set(flatDmgReduceKey, PersistentDataType.DOUBLE, v);
            case "최대 생명력" -> pdc.set(maxHealthKey, PersistentDataType.DOUBLE, v);
            case "공격 속도 증가" -> pdc.set(attackSpeedKey, PersistentDataType.DOUBLE, v);
            case "이동 속도" -> pdc.set(moveSpeedKey, PersistentDataType.DOUBLE, v);
            case "악마 추가 피해" -> pdc.set(demonDmgKey, PersistentDataType.DOUBLE, v);
            case "언데드 추가 피해" -> pdc.set(undeadDmgKey, PersistentDataType.DOUBLE, v);
            case "대상 둔화" -> pdc.set(slowPctKey, PersistentDataType.DOUBLE, v);
            default -> { /* 미구현 옵션: 로어만 표시 */ }
        }
    }

    // ===================================================================
    //  유니크 아이템 (0.3.6): config.yml의 uniques 섹션을 읽어 생성
    //  - options: 옵션 종류는 고정, 수치는 min~max 안에서 생성 시 한 번 굴림
    //  - damage-ranges: 최소/최대 피해가 각각 범위로 굴러가고, 타격마다 그 사이에서 다시 굴림
    //  - 효과가 아직 없는 옵션(힘, 명중률 등)은 로어에만 표시된다.
    // ===================================================================

    private ItemStack createUniqueItem(String id) {
        ConfigurationSection u = getConfig().getConfigurationSection("uniques." + id);
        if (u == null) return null;
        Material material = Material.matchMaterial(u.getString("base", "DIAMOND_SWORD"));
        if (material == null || !material.isItem()) return null;

        String displayName = u.getString("name", id);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(rarityKey, PersistentDataType.STRING, "unique");
        meta.setDisplayName(rarityColor("unique") + displayName);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "등급: " + getConfig().getString("rarities.unique.display", "유니크"));
        lore.add(ChatColor.DARK_GRAY + prettyName(material));

        ConfigurationSection options = u.getConfigurationSection("options");
        if (options != null) {
            for (String optName : options.getKeys(false)) {
                ConfigurationSection opt = options.getConfigurationSection(optName);
                if (opt == null) continue;
                int value = rollInt(opt.getInt("min", 0), opt.getInt("max", opt.getInt("min", 0)));
                lore.add(ChatColor.GOLD + optName + " +" + value + opt.getString("suffix", ""));
                applyOptionToPdc(meta, optName, value);
            }
        }

        ConfigurationSection ranges = u.getConfigurationSection("damage-ranges");
        if (ranges != null) {
            for (String kor : ranges.getKeys(false)) {
                String key = damageRangeKey(kor);
                ConfigurationSection r = ranges.getConfigurationSection(kor);
                if (key == null || r == null) continue;
                int minLow = r.getInt("min-low", 0);
                int minHigh = r.getInt("min-high", minLow);
                int maxLow = r.getInt("max-low", minHigh);
                int maxHigh = r.getInt("max-high", maxLow);
                int lo = rollInt(minLow, minHigh);
                int hi = Math.max(lo, rollInt(maxLow, maxHigh));
                pdc.set(new NamespacedKey(this, "range_" + key + "_min"), PersistentDataType.DOUBLE, (double) lo);
                pdc.set(new NamespacedKey(this, "range_" + key + "_max"), PersistentDataType.DOUBLE, (double) hi);
                lore.add(ChatColor.GOLD + kor + " 피해 +" + lo + "-" + hi + " 추가");
            }
        }

        // 공격 속도/최대 생명력/이동 속도는 AttributeModifier로 장비에 부착.
        applyAttributeModifiers(meta);

        lore.add(ChatColor.DARK_GRAY + "DiabloRPG 0.3.8");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** config의 uniques 섹션에 등록된 ID 목록 (오류 안내용). */
    private String uniqueIds() {
        ConfigurationSection section = getConfig().getConfigurationSection("uniques");
        if (section == null || section.getKeys(false).isEmpty()) return "(없음)";
        return String.join(", ", section.getKeys(false));
    }

    private int rollInt(int low, int high) {
        if (high <= low) return low;
        return low + random.nextInt(high - low + 1);
    }

    /** 한글 피해 종류 → PDC 키 이름. 알 수 없으면 null. */
    private String damageRangeKey(String korean) {
        return switch (korean) {
            case "물리" -> "physical";
            case "화염" -> "fire";
            case "냉기" -> "cold";
            case "번개" -> "lightning";
            case "독" -> "poison";
            case "비전" -> "arcane";
            default -> null;
        };
    }

    /** 무기/화살 PDC의 최소~최대 범위 피해를 한 번 굴린다. 범위가 없으면 0. */
    private double rollDamageRange(PersistentDataContainer pdc, String key) {
        Double lo = pdc.get(new NamespacedKey(this, "range_" + key + "_min"), PersistentDataType.DOUBLE);
        Double hi = pdc.get(new NamespacedKey(this, "range_" + key + "_max"), PersistentDataType.DOUBLE);
        if (lo == null || hi == null) return 0.0;
        if (hi <= lo) return lo;
        return lo + random.nextDouble() * (hi - lo);
    }

    // ===================================================================
    //  샌드백 (0.3.7): /rpgdummy spawn <normal|undead|demon> [방어%]  /  /rpgdummy clear
    //  - AI/이동/소리를 끄고, 체력 1024로 맞아도 죽지 않게 매 타격 후 회복한다.
    //  - 종류별 몹 타입: normal=철 골렘, undead=좀비(언데드 판정), demon=블레이즈(악마 판정)
    //    -> 악마/언데드 추가 피해 옵션 테스트용.
    //  - 이름표에 마지막 피해, 타격 횟수, 누적 피해가 표시된다.
    // ===================================================================

    private boolean isDummy(Entity entity) {
        return entity.getPersistentDataContainer().has(dummyKey, PersistentDataType.STRING);
    }

    private String dummyKindLabel(String kind) {
        return switch (kind) {
            case "undead" -> "언데드";
            case "demon" -> "악마";
            default -> "일반";
        };
    }

    private boolean handleRpgDummy(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("clear")) {
            int removed = 0;
            for (World w : Bukkit.getWorlds()) {
                for (LivingEntity le : w.getLivingEntities()) {
                    if (isDummy(le)) {
                        le.remove();
                        removed++;
                    }
                }
            }
            sender.sendMessage(ChatColor.GREEN + "샌드백 " + removed + "개를 제거했습니다.");
            return true;
        }

        if (args.length < 2 || !args[0].equalsIgnoreCase("spawn")) {
            sender.sendMessage(ChatColor.YELLOW + "사용법: /rpgdummy spawn <normal|undead|demon> [방어%]");
            sender.sendMessage(ChatColor.YELLOW + "        /rpgdummy clear  (모든 샌드백 제거)");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "게임 안의 플레이어만 샌드백을 소환할 수 있습니다.");
            return true;
        }

        String kind = args[1].toLowerCase(Locale.ROOT);
        EntityType type;
        switch (kind) {
            case "normal" -> type = EntityType.IRON_GOLEM;
            case "undead" -> type = EntityType.ZOMBIE;
            case "demon" -> type = EntityType.BLAZE;
            default -> {
                sender.sendMessage(ChatColor.RED + "종류는 normal, undead, demon 중 하나입니다.");
                return true;
            }
        }

        double defense = 5.0; // 일반 몹 기본 가상 방어력과 같은 값
        if (args.length >= 3) {
            try {
                defense = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "방어%는 숫자로 입력하세요. 예: /rpgdummy spawn undead 25");
                return true;
            }
        }

        // 플레이어 앞 3칸, 같은 높이에 소환하고 플레이어를 바라보게 한다.
        Location base = player.getLocation();
        Vector dir = base.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-6) dir = new Vector(0, 0, 1);
        Location loc = base.clone().add(dir.normalize().multiply(3));
        loc.setY(base.getY());
        loc.setYaw(base.getYaw() + 180f);
        loc.setPitch(0f);

        LivingEntity dummy = (LivingEntity) player.getWorld().spawnEntity(loc, type);
        dummy.setAI(false);
        dummy.setGravity(false);
        dummy.setSilent(true);
        dummy.setPersistent(true);
        dummy.setRemoveWhenFarAway(false);
        dummy.setCanPickupItems(false);
        dummy.setCustomNameVisible(true);

        AttributeInstance maxHp = dummy.getAttribute(VersionCompat.MAX_HEALTH);
        if (maxHp != null) maxHp.setBaseValue(1024.0);
        dummy.setHealth(1024.0);
        // 바닐라 방어구/넉백이 피해 수치를 흐리지 않도록 0으로 맞춘다. (RPG 가상 방어%만 적용)
        AttributeInstance armor = dummy.getAttribute(VersionCompat.ARMOR);
        if (armor != null) armor.setBaseValue(0.0);
        AttributeInstance kb = dummy.getAttribute(VersionCompat.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.setBaseValue(1.0);

        // 좀비는 낮에 타지 않도록 부서지지 않는 가죽 모자를 씌운다. (불 피해는 아래 리스너에서도 막는다)
        if (type == EntityType.ZOMBIE && dummy.getEquipment() != null) {
            ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
            ItemMeta helmetMeta = helmet.getItemMeta();
            helmetMeta.setUnbreakable(true);
            helmet.setItemMeta(helmetMeta);
            dummy.getEquipment().setHelmet(helmet);
            dummy.getEquipment().setHelmetDropChance(0f);
        }

        PersistentDataContainer pdc = dummy.getPersistentDataContainer();
        pdc.set(dummyKey, PersistentDataType.STRING, kind);
        pdc.set(dummyDefenseKey, PersistentDataType.DOUBLE, defense);
        pdc.set(dummyTotalKey, PersistentDataType.DOUBLE, 0.0);
        pdc.set(dummyHitsKey, PersistentDataType.DOUBLE, 0.0);
        updateDummyName(dummy, 0.0);

        sender.sendMessage(ChatColor.GREEN + dummyKindLabel(kind) + " 샌드백을 소환했습니다 (방어 "
                + fmt(defense) + "%).");
        return true;
    }

    private void updateDummyName(LivingEntity dummy, double lastDamage) {
        PersistentDataContainer pdc = dummy.getPersistentDataContainer();
        String kind = pdc.getOrDefault(dummyKey, PersistentDataType.STRING, "normal");
        double def = pdc.getOrDefault(dummyDefenseKey, PersistentDataType.DOUBLE, 5.0);
        double total = pdc.getOrDefault(dummyTotalKey, PersistentDataType.DOUBLE, 0.0);
        double hits = pdc.getOrDefault(dummyHitsKey, PersistentDataType.DOUBLE, 0.0);
        dummy.setCustomName(ChatColor.YELLOW + "[샌드백·" + dummyKindLabel(kind) + "] "
                + ChatColor.WHITE + "방어 " + fmt(def) + "% "
                + ChatColor.RED + "최근 " + fmt(lastDamage)
                + ChatColor.GRAY + " (" + (int) hits + "타 / 누적 " + fmt(total) + ")");
    }

    /**
     * 샌드백이 맞은 뒤 처리(MONITOR: 다른 계산이 모두 끝난 최종 피해 기준).
     * - 환경 피해(불, 용암, 낙하 등)는 막는다.
     * - 마지막 피해/누적 피해/타격 수를 기록해 이름표에 표시한다.
     * - 죽을 만한 피해는 취소하고, 맞은 다음 틱에 체력을 가득 채운다.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDummyDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity dummy) || !isDummy(dummy)) return;

        if (!(event instanceof EntityDamageByEntityEvent)) {
            event.setCancelled(true);
            dummy.setFireTicks(0);
            return;
        }

        double dmg = event.getFinalDamage();
        PersistentDataContainer pdc = dummy.getPersistentDataContainer();
        pdc.set(dummyTotalKey, PersistentDataType.DOUBLE,
                pdc.getOrDefault(dummyTotalKey, PersistentDataType.DOUBLE, 0.0) + dmg);
        pdc.set(dummyHitsKey, PersistentDataType.DOUBLE,
                pdc.getOrDefault(dummyHitsKey, PersistentDataType.DOUBLE, 0.0) + 1.0);
        updateDummyName(dummy, dmg);

        if (dmg >= dummy.getHealth()) {
            event.setCancelled(true); // 죽지 않게 막는다 (피해 기록은 위에서 이미 남김)
        }

        Bukkit.getScheduler().runTask(this, () -> {
            if (dummy.isDead()) return;
            AttributeInstance hp = dummy.getAttribute(VersionCompat.MAX_HEALTH);
            dummy.setHealth(hp == null ? 1024.0 : hp.getValue());
        });
    }

    /** 샌드백이 불에 붙지 않게 한다 (좀비 낮 화상 등). */
    @EventHandler(ignoreCancelled = true)
    public void onDummyCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof LivingEntity le && isDummy(le)) {
            event.setCancelled(true);
        }
    }

    private List<String> elementsConfig() {
        List<String> list = getConfig().getStringList("elements");
        return list.isEmpty() ? DEFAULT_ELEMENTS : list;
    }

    private String randomElement() {
        List<String> list = elementsConfig();
        return list.get(random.nextInt(list.size()));
    }

    private ChatColor rarityColor(String rarity) {
        return switch (rarity) {
            case "magic" -> ChatColor.AQUA;
            case "rare" -> ChatColor.YELLOW;
            case "unique" -> ChatColor.GOLD;
            case "set" -> ChatColor.GREEN;
            case "runeword" -> ChatColor.LIGHT_PURPLE;
            default -> ChatColor.WHITE; // normal
        };
    }

    private String prettyName(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }
}
