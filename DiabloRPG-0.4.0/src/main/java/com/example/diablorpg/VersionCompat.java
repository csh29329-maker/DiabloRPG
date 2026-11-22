package com.example.diablorpg;

import org.bukkit.attribute.Attribute;

/**
 * 마인크래프트 버전마다 이름이 바뀌는 API 상수를 한 곳에 모은다.
 *
 * 1.21.1 API: Attribute.GENERIC_MAX_HEALTH 처럼 GENERIC_ 접두어가 붙어 있다.
 * 1.21.3 이상 API: 접두어가 빠져 Attribute.MAX_HEALTH 가 된다.
 * 대상 버전을 바꿀 때는 이 파일의 다섯 줄만 고치면 된다.
 */
final class VersionCompat {

    private VersionCompat() {}

    static final Attribute MAX_HEALTH = Attribute.GENERIC_MAX_HEALTH;
    static final Attribute ATTACK_SPEED = Attribute.GENERIC_ATTACK_SPEED;
    static final Attribute MOVEMENT_SPEED = Attribute.GENERIC_MOVEMENT_SPEED;
    static final Attribute ARMOR = Attribute.GENERIC_ARMOR;
    static final Attribute KNOCKBACK_RESISTANCE = Attribute.GENERIC_KNOCKBACK_RESISTANCE;
}
