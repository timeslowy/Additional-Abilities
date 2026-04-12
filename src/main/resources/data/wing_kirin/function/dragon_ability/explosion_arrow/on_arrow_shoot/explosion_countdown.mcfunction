## 执行者：玩家
# 加标签以供选择
execute as @e[type=dragonsurvival:generic_arrow_entity,nbt={general_data:{name:"wing_kirin:explosion_arrow"}},distance=..6] \
    run tag @s add explosion_arrow

# 计时器
execute as @e[type=dragonsurvival:generic_arrow_entity,tag=explosion_arrow,distance=..6] \
    run scoreboard players set @s wk.explosion_arrow.countdown 100

# 发光
execute as @e[type=dragonsurvival:generic_arrow_entity,tag=explosion_arrow,distance=6..] \
    run data modify entity @s Glowing set value true

# 播放TNT点燃声音
execute as @e[type=dragonsurvival:generic_arrow_entity,tag=explosion_arrow,distance=6..] at @s \
    run playsound entity.tnt.primed block @a