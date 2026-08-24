plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.github.ghactions"
version = "0.1.3"

repositories {
    // 不用 mavenCentral()：部分网络下 repo1.maven.org 会按 TLS 指纹被阻断——
    // curl 能连、Java 握手却被终止，构建报 SSLHandshakeException。
    // cache-redirector 亦时通时断，故把实测稳定的镜像放在首位，后者作为备选。
    maven("https://maven.aliyun.com/repository/public")
    maven("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2")
    intellijPlatform {
        defaultRepositories()
    }
}

// 优先使用本机已安装的 IDE 作为 SDK。
// 从 2025.3 起 IDEA Community 不再单独发布 artifact，Gradle 插件转而下载完整的
// 安装包（macOS 上是 1~2GB 的 .dmg）；在下载受限的网络下这一步会直接失败。
// 而本机既然装着目标版本的 IDE，直接拿它当 SDK 更快，版本也与实际运行环境一致。
// 未指定且本地找不到时回落到下载，CI 等环境不受影响。
val localIdePath: String? = (providers.gradleProperty("localIdePath").orNull
    ?: "/Applications/IntelliJ IDEA.app".takeIf { File(it).exists() })

dependencies {
    intellijPlatform {
        if (localIdePath != null) local(localIdePath) else intellijIdea("2026.2")
        bundledPlugin("Git4Idea")
        // GitRepositoryManager 的超类型链散落在三个平台模块里，缺一个都编译不过：
        //   Repository / RepositoryManager      -> intellij.platform.vcs.dvcs
        //   AbstractRepositoryManager           -> intellij.platform.vcs.dvcs.impl
        //   （2026.2 起部分实现迁到 .impl.shared）
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.dvcs.impl.shared")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // 必须与平台捆绑的 coroutines-core 版本一致（2026.2 为 1.10.2）。
    // 混用会在测试中抛 CoroutinesInternalError（协程内部机制的致命错误）。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Gradle 9 起不再自动提供 JUnit Platform launcher，需显式声明，
    // 否则测试任务以 "Failed to load JUnit Platform" 启动失败
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    // gradle.properties 里关掉了 stdlib 默认依赖（插件产物由平台提供 stdlib，不该重复打包），
    // 但测试运行时没有平台兜底，只能捡传递依赖带来的旧版本，于是解析不了新编译器产生的
    // debug metadata，协程一被取消就抛 "Debug metadata version mismatch"。
    // 这里为测试单独补上与编译器同版本的 stdlib，不影响插件产物。
    testRuntimeOnly(kotlin("stdlib"))
}

intellijPlatform {
    // 关闭 GUI Forms 字节码增强：本插件是纯 Kotlin，没有任何 .form 文件。
    // 开着它会为此额外下载一个编译器 jar，纯属无谓的构建依赖。
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
