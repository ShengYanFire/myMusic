pluginManagement {
    repositories {
        // China mirrors first (Aliyun)
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // Huawei Cloud mirror (fallback)
        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }
        // Official repos (last resort)
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
        // China mirrors first (Aliyun)
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // Huawei Cloud mirror (fallback)
        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }
        // Official repos (last resort)
        google()
        mavenCentral()
    }
}

rootProject.name = "MyMusic"
include(":app")
