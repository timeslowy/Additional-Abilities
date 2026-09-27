### 2.0.0

#### 中文

1. 将原有海洋龙和洞穴龙的额外技能的命名空间分别转至`sea_dragon`和`cave_dragon`下；
2. 更新了大量自定义技能组件，详见README；
3. 新增森林龙技能「草木皆兵」（`forest_dragon:natural_alies`）及配套伤害类型 `forest_dragon:natural_force`；
4. `simple_screen_vision` 实体效果新增「边光」视觉类型（`edge_light`）—— 屏幕边缘由边向内渐隐的遮罩，厚度 / 不透明度 / 颜色均可调，并随之新增 `size`、`color` 两个字段。

#### English

1. Changed the namespace of Sea Dragon and Cave Dragon 's additional abilities separately to `sea_dragon`&`cave_dragon`
2. New custom components abilitiy, please see README;
3. New ability for Forest Dragon: 'Natural Allies'(`forest_dragon:natural_alies`) with the damage type `forest_dragon:natural_force`;
4. New "Edge Light" vision type (`edge_light`) for the `simple_screen_vision` entity effect — an inward-fading mask along the screen edges with adjustable thickness, opacity and colour, along with the new `size` and `color` fields.