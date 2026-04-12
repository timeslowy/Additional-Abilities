## 执行者：射出的箭雷实体
# 倒计时归零产生爆炸
execute if score @s wk.explosion_arrow.countdown matches ..0 run function wing_kirin:dragon_ability/explosion_arrow/explosion/main

# 自毙
execute if score @s wk.explosion_arrow.countdown matches ..0 run return run kill @s

# 处理计时器
scoreboard players remove @s wk.explosion_arrow.countdown 1