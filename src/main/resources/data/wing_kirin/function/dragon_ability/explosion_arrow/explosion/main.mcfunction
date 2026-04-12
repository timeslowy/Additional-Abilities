## 执行者：射出的箭雷实体
# 将发射者UUID拷贝至命令存储
data modify storage wing_kirin:ram explosion_arrow.Owner set from entity @s Owner

# 生成顺爆实体火球
function wing_kirin:dragon_ability/explosion_arrow/explosion/explosion_resource

# 向其传入发射者等参数
execute as @n[type=dragonsurvival:generic_ball_entity,tag=explosion_arrow_summon] at @s \
    run function wing_kirin:dragon_ability/explosion_arrow/explosion/explosion_setting