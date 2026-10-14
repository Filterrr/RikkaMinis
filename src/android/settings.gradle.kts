pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // [backup-upstream-1.14] rclone.aar — the backup feature's remote
        // destinations (SMB / WebDAV / SFTP / S3 / FTP). Not published to any
        // Maven repo: built from deps/rclone-mobile by deps/build_rclone_android.sh
        // into app/libs/ (build artifact, not a checked-in binary).
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Minis"
include(":app")
