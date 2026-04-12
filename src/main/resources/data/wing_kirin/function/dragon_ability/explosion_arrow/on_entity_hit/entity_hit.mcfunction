## 初始执行者：被击中的实体
## 执行位置：被击中实体
# 将箭雷的计时器减少为1秒（20刻），不足一秒则不进行操作
execute as @e[type=dragonsurvival:generic_arrow_entity,tag=explosion_arrow,distance=..1] \
    if score @s wk.explosion_arrow.countdown matches 20.. run scoreboard players set @s wk.explosion_arrow.countdown 20