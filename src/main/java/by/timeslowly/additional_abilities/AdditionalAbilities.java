package by.timeslowly.additional_abilities;

import by.timeslowly.additional_abilities.common.AAClientSetup;
import by.timeslowly.additional_abilities.common.network.AANetwork;
import by.timeslowly.additional_abilities.registry.*;
import by.timeslowly.additional_abilities.registry.dragon.ability.*;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.trigger.OnBlockPlaced;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.trigger.OnItemConsumed;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

// 必须匹配 META-INF/neoforge.mods.toml file
// TODO：给测试技能补上简单图标
// TODO：决定使用的协议？
// TODO:优化加载性能开销？疑似会使得同个实例初次进入世界时卡顿？
@Mod(AdditionalAbilities.MOD_ID)
public class AdditionalAbilities {
    // 定义模组ID以供他处调用
    public static final String MOD_ID = "additional_abilities";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();


    // 注册模组加载的通用内容设置（部分需要顺序）
    public AdditionalAbilities(IEventBus modEventBus, @NotNull ModContainer modContainer) {
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
        // 注册龙之技能自定义被动触发类型（DragonSurvival activation_trigger 注册表，trigger_type）
        AAAbilityTriggers.register(modEventBus);
        // 接线：放置方块事件 → 本模组 additional_abilities:on_block_placed 触发器分发
        // （触发器自身不订阅事件，需自行接线；DS 内置触发器同样集中在主类构造函数里挂）
        NeoForge.EVENT_BUS.addListener(OnBlockPlaced::trigger);
        // 接线：使用物品完成事件 → 本模组 additional_abilities:on_item_consumed 触发器分发
        // （LivingEntityUseItemEvent.Finish 对全部生物抛出，触发器内部自行筛「服务端玩家 + 龙形态」）
        NeoForge.EVENT_BUS.addListener(OnItemConsumed::trigger);
        // 注册网络通道（屏幕视觉 / 方块震动 的服务端 → 客户端同步）
        modEventBus.addListener(AANetwork::register);
        // 注册客户端专属内容（蓄力档位 HUD 图层；内部自带物理端判定，服务端不会加载客户端类型）
        AAClientSetup.register(modEventBus);
    }

}
