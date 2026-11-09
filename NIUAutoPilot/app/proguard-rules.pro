# 小牛靠近开机助手 R8 混淆规则
# 本项目无反射/序列化，默认规则 + AGP 自动注入的 Kotlin keep 已足够。
# 如后续遇到混淆导致的崩溃，按类名在此追加 -keep。

# 保留源码行号，便于崩溃日志定位
-keepattributes SourceFile,LineNumberTable

# 保留车辆状态机 sealed class（供 when/instanceof 判定）
-keep class com.niu.autopilot.vehicle.VehicleState** { *; }
