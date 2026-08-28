pluginManagement {
    repositories {
        // 国内镜像（优先：阿里云）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 华为云镜像（兜底）
        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }
        // 官方源（最后兜底）
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像（优先：阿里云）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 华为云镜像（兜底）
        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }
        // 官方源（最后兜底）
        google()
        mavenCentral()
    }
}

rootProject.name = "MyMusic"
include(":app")
