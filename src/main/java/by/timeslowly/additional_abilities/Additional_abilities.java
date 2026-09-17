package by.timeslowly.additional_abilities;

import by.timeslowly.additional_abilities.common.AAClientSetup;
import by.timeslowly.additional_abilities.common.network.AANetwork;
import by.timeslowly.additional_abilities.registry.*;
import by.timeslowly.additional_abilities.registry.dragon.ability.AAAbilityActivations;
import by.timeslowly.additional_abilities.registry.dragon.ability.AAAbilityBlockEffects;
import by.timeslowly.additional_abilities.registry.dragon.ability.AAAbilityEntityEffects;
import by.timeslowly.additional_abilities.registry.dragon.ability.AAAbilityTargets;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

// 必须匹配 META-INF/neoforge.mods.toml file
// TODO:尝试注册自定义被动触发器（这个连文档都还没有）
// TODO：写使用文档
// TODO：给测试技能补上描述与简单图标
@Mod(Additional_abilities.MOD_ID)
public class Additional_abilities {
    // 定义模组ID以供他处调用
    public static final String MOD_ID = "additional_abilities";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();


    // 注册模组加载的通用内容设置（部分需要顺序）
    public Additional_abilities(IEventBus modEventBus, @NotNull ModContainer modContainer) {
        // 注册实体附加数据（伤害反震参数载体）
        AAAttachments.register(modEventBus);
        // 注册自定义伤害类型
        AADamageTypes.register(modEventBus);
        // 注册龙之技能自定义实体效果类型（DragonSurvival ability_entity_effect 注册表）
        AAAbilityEntityEffects.register(modEventBus);
        // 注册龙之技能自定义方块效果类型（DragonSurvival ability_block_effect 注册表）
        AAAbilityBlockEffects.register(modEventBus);
        // 注册龙之技能自定义激活类型（DragonSurvival activation 注册表）
        AAAbilityActivations.register(modEventBus);
        // 注册龙之技能自定义目标选择器类型（DragonSurvival ability_targeting 注册表，target_type）
        AAAbilityTargets.register(modEventBus);
        // 注册网络通道（屏幕视觉 / 方块震动 的服务端 → 客户端同步）
        modEventBus.addListener(AANetwork::register);
        // 注册客户端专属内容（蓄力档位 HUD 图层；内部自带物理端判定，服务端不会加载客户端类型）
        AAClientSetup.register(modEventBus);
    }

}
