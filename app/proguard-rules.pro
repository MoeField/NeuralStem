# NeuralStem R8 keep rules
#
# 重点：JNI native 方法、JNI FindClass 的 Kotlin 类、反射桥、可选 NCM 模块。

# JNI：保留所有带 native 方法的类/方法名。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# JNI 通过 FindClass 构造的结果类。
-keep class pub.miku.neuralstem.spike.SherpaSourceSeparation$SeparationResult {
    <init>(float[], float[], int);
}

# main 源集 ↔ NCM 源集的反射桥；类名/方法名必须稳定。
-keep class pub.miku.neuralstem.player.Avs3Bridge {
    *;
}

# 可选 NCM 模块（默认不编译；with_ncm 构建时也不能被 R8 改名/裁掉）。
-keep class pub.miku.neuralstem.experimental.ncm.** {
    *;
}

# Kotlin 反射/注解元数据（Media3/Compose 等依赖可能需要）。
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# 可选：保持 Compose 运行时需要的 SourceFile/LineNumber（便于崩溃定位）。
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
