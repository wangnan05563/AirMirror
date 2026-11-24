# T06123 release R8 规则
# 保留崩溃栈可读性；业务代码无反射/序列化，不额外 keep 整包。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
