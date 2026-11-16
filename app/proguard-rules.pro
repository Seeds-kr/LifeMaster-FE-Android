# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# LifeMaster - Retrofit R8 설정
# Retrofit 제네릭 타입 및 어노테이션 보존
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# NetworkService API 인터페이스 보존
-keep interface com.example.lifemaster.network.NetworkService {
    *;
}

# Retrofit Call 제네릭 타입 보존
-keep interface retrofit2.Call {
    *;
}

# Retrofit Response 타입 보존
-keep class retrofit2.Response {
    *;
}

# Kotlin suspend 함수 타입 정보 보존
-keep class kotlin.coroutines.Continuation {
    *;
}


# LifeMaster - Gson 데이터 모델 보호
# Gson JSON 변환에 사용되는 모델 보존
-keep class com.example.lifemaster.presentation.**.model.** {
    *;
}