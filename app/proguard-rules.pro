# Shizuku 通过类名反射创建 UserService，必须保留类和构造函数。
-keep class io.github.rwxzxy.guard.shizuku.ShellUserService { <init>(...); }
-keep class io.github.rwxzxy.guard.shizuku.IShellService { *; }
-keep class io.github.rwxzxy.guard.shizuku.IShellService$* { *; }

# kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class io.github.rwxzxy.guard.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.rwxzxy.guard.**$$serializer { *; }
