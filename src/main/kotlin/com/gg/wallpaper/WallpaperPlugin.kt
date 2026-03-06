@file:OptIn(UnstableSpwWorkshopApi::class)
@file:Suppress("unused")

package com.gg.wallpaper

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.io.path.readText

class WallpaperPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {
    override fun start() {
        WorkshopApi.ui.toast("壁纸插件已启动", WorkshopApi.Ui.ToastType.Success)
    }

    override fun stop() {
        WorkshopApi.ui.toast("壁纸插件已停止", WorkshopApi.Ui.ToastType.Warning)
    }

    companion object {
        private const val CONFIG_PATH = "wallpaper/config.json"
        private const val KEY_INSTALL_PATH = "salt_player.path"
        private const val LEGACY_KEY_STEAM_PATH = "steam.path"
        private const val KEY_WALLPAPER_PATH = "wallpaper.path"
        private const val SALT_PLAYER_FOLDER = "Salt Player for Windows"
        private const val TARGET_RELATIVE_PATH = "app/resources/bg_wallpaper.jpg"
        private const val STEAM_COMMON_SALT_PLAYER_PATH = "steamapps/common/$SALT_PLAYER_FOLDER"
        private const val BACKUP_FILE_NAME = "bg_wallpaper.original.backup.jpg"

        @JvmStatic
        @JvmName("detectSteamPath")
        fun detectSteamPath() {
            val config = loadConfig()
            config.reload()
            val configuredPath = getConfiguredInstallPath(config)
            val detected = detectBestSaltPlayerPath(configuredPath)
            if (detected != null) {
                saveInstallPath(config, detected)
                config.save()
                WorkshopApi.ui.toast("已检测到路径：${detected.pathString}", WorkshopApi.Ui.ToastType.Success)
                return
            }
            WorkshopApi.ui.toast("未检测到 Salt Player 安装路径，请手动选择本软件目录内任意文件", WorkshopApi.Ui.ToastType.Warning)
        }

        @JvmStatic
        @JvmName("chooseSteamPath")
        fun chooseSteamPath() {
            runCatching {
                val config = loadConfig()
                config.reload()
                val selectedFile = chooseNativeFile(
                    initialPath = getConfiguredInstallPath(config),
                    title = "选择 Salt Player 路径（请选择软件目录内任意文件）"
                )
                if (selectedFile != null) {
                    val installPath = findSaltPlayerDirFromSelectedFile(selectedFile)
                        ?: detectBestSaltPlayerPath(selectedFile.pathString)
                        ?: error("未能定位 Salt Player 目录，请选择 Salt Player for Windows 目录内任意文件")
                    saveInstallPath(config, installPath)
                    config.save()
                    WorkshopApi.ui.toast("Salt Player 路径已设置：${installPath.pathString}", WorkshopApi.Ui.ToastType.Success)
                    return
                }
                WorkshopApi.ui.toast("已取消选择 Salt Player 路径", WorkshopApi.Ui.ToastType.Warning)
            }.onFailure {
                WorkshopApi.ui.toast("选择失败：${it.message}", WorkshopApi.Ui.ToastType.Error)
            }
        }

        @JvmStatic
        @JvmName("chooseWallpaper")
        fun chooseWallpaper() {
            runCatching {
                val config = loadConfig()
                config.reload()
                val selected = chooseNativeFile(
                    initialPath = config.get(KEY_WALLPAPER_PATH, ""),
                    title = "选择新壁纸",
                    extensions = setOf("jpg", "jpeg")
                )
                if (selected == null) {
                    WorkshopApi.ui.toast("已取消选择新壁纸", WorkshopApi.Ui.ToastType.Warning)
                    return
                }
                config.set(KEY_WALLPAPER_PATH, selected.pathString)
                config.save()
                applyWallpaperWithPath(config, selected.pathString)
            }.onFailure {
                WorkshopApi.ui.toast("选择失败：${it.message}", WorkshopApi.Ui.ToastType.Error)
            }
        }

        @JvmStatic
        @JvmName("restoreDefaultWallpaper")
        fun restoreDefaultWallpaper() {
            runCatching {
                val config = loadConfig()
                config.reload()
                val target = resolveTargetWallpaper(getConfiguredInstallPath(config))
                val backup = target.parent.resolve(BACKUP_FILE_NAME)
                require(backup.exists() && backup.isRegularFile()) { "未找到默认备份，请先执行一次替换" }

                Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING)
                WorkshopApi.ui.toast("已恢复默认壁纸", WorkshopApi.Ui.ToastType.Success)
            }.onFailure {
                WorkshopApi.ui.toast("恢复失败：${it.message}", WorkshopApi.Ui.ToastType.Error)
            }
        }

        private fun loadConfig(): ConfigHelper {
            return WorkshopApi.manager.createConfigManager().getConfig(CONFIG_PATH)
        }

        private fun applyWallpaperWithPath(config: ConfigHelper, wallpaperPath: String) {
            val source = Paths.get(wallpaperPath).normalize()
            require(source.exists() && source.isRegularFile()) { "壁纸文件不存在：$wallpaperPath" }

            val target = resolveTargetWallpaper(getConfiguredInstallPath(config))
            require(target.exists() && target.isRegularFile()) { "目标文件不存在：${target.pathString}" }

            ensureBackup(target)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)

            WorkshopApi.ui.toast("壁纸替换成功", WorkshopApi.Ui.ToastType.Success)
        }

        private fun ensureBackup(target: Path) {
            val backup = target.parent.resolve(BACKUP_FILE_NAME)
            if (!backup.exists()) {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun chooseNativeFile(
            initialPath: String,
            title: String,
            extensions: Set<String>? = null
        ): Path? {
            require(!GraphicsEnvironment.isHeadless()) { "当前环境不支持图形文件选择器" }
            val frame = Frame()
            return try {
                val dialog = FileDialog(frame, title, FileDialog.LOAD).apply {
                    resolveInitialPath(initialPath)?.let { path ->
                        if (path.toFile().isDirectory) {
                            directory = path.pathString
                        } else {
                            directory = path.parent?.pathString
                            file = path.fileName?.toString()
                        }
                    }
                    if (!extensions.isNullOrEmpty()) {
                        val allow = extensions.map { it.lowercase() }.toSet()
                        filenameFilter = java.io.FilenameFilter { _, name ->
                            val ext = name.substringAfterLast('.', "").lowercase()
                            ext in allow
                        }
                    }
                }
                dialog.isVisible = true
                val selectedName = dialog.file ?: return null
                val selectedDir = dialog.directory ?: return null
                Paths.get(selectedDir, selectedName).normalize()
            } finally {
                frame.dispose()
            }
        }

        private fun resolveInitialPath(initialPath: String): Path? {
            return pathFromConfig(initialPath)?.takeIf { it.exists() }
        }

        private fun resolveTargetWallpaper(configuredPath: String): Path {
            val installPath = detectBestSaltPlayerPath(configuredPath)
                ?: error("未找到 Salt Player 路径，请手动选择本软件目录内任意文件")
            return installPath.resolve(TARGET_RELATIVE_PATH).normalize()
        }

        private fun detectBestSaltPlayerPath(configuredPath: String): Path? {
            val candidates = collectSaltPlayerDirs(configuredPath)
            val withWallpaper = candidates.firstOrNull {
                val target = it.resolve(TARGET_RELATIVE_PATH).normalize()
                target.exists() && target.isRegularFile()
            }
            return withWallpaper ?: candidates.firstOrNull()
        }

        private fun collectSaltPlayerDirs(configuredPath: String): List<Path> {
            val saltCandidates = LinkedHashSet<Path>()
            val steamRoots = collectSteamRoots(configuredPath)

            pathFromConfig(configuredPath)?.let { root ->
                if (root.fileName?.toString().equals(SALT_PLAYER_FOLDER, ignoreCase = true)) {
                    saltCandidates.add(root)
                }
            }

            steamRoots.forEach { root ->
                saltCandidates.add(root.resolve(STEAM_COMMON_SALT_PLAYER_PATH).normalize())
            }

            return saltCandidates
                .map { it.normalize() }
                .filter { it.fileName?.toString().equals(SALT_PLAYER_FOLDER, ignoreCase = true) }
        }

        private fun collectSteamRoots(configuredPath: String): List<Path> {
            val candidates = LinkedHashSet<Path>()
            pathFromConfig(configuredPath)?.let { addSteamRootCandidate(candidates, it) }
            envPath("STEAM_DIR")?.let { addSteamRootCandidate(candidates, it) }
            envPath("ProgramFiles(x86)")?.resolve("Steam")?.let { addSteamRootCandidate(candidates, it) }
            envPath("ProgramFiles")?.resolve("Steam")?.let { addSteamRootCandidate(candidates, it) }
            Paths.get("C:\\Steam").let { addSteamRootCandidate(candidates, it) }

            discoverCommonSteamDirs().forEach { addSteamRootCandidate(candidates, it) }
            readLibraryFolders(candidates).forEach { addSteamRootCandidate(candidates, it) }

            return candidates
                .map { it.normalize() }
                .filter { isSteamRoot(it) }
        }

        private fun addSteamRootCandidate(candidates: MutableSet<Path>, path: Path) {
            candidates.add(path)
            if (path.fileName?.toString().equals("steamapps", ignoreCase = true)) {
                path.parent?.let { candidates.add(it) }
            }
            path.resolve("Steam").let { candidates.add(it) }
        }

        private fun discoverCommonSteamDirs(): List<Path> {
            val result = LinkedHashSet<Path>()
            File.listRoots().orEmpty().forEach { root ->
                result.add(root.toPath().resolve("Steam"))
                result.add(root.toPath().resolve("Program Files (x86)/Steam"))
                result.add(root.toPath().resolve("Program Files/Steam"))
            }
            return result.toList()
        }

        private fun pathFromConfig(configuredPath: String): Path? {
            if (configuredPath.isBlank()) return null
            val input = Paths.get(configuredPath).normalize()
            findSaltPlayerDirFromSelectedFile(input)?.let { return it }
            if (input.fileName?.toString().equals("steam.exe", ignoreCase = true)) {
                return input.parent
            }
            if (input.fileName?.toString().equals("steamapps", ignoreCase = true)) {
                return input.parent
            }
            if (input.fileName?.toString().equals("Steam", ignoreCase = true)) {
                return input
            }
            if (input.fileName?.toString().equals(SALT_PLAYER_FOLDER, ignoreCase = true)) {
                return input.parent?.parent?.parent
            }
            return input
        }

        private fun getConfiguredInstallPath(config: ConfigHelper): String {
            val current = config.get(KEY_INSTALL_PATH, "").trim()
            if (current.isNotEmpty()) return current
            return config.get(LEGACY_KEY_STEAM_PATH, "").trim()
        }

        private fun saveInstallPath(config: ConfigHelper, installPath: Path) {
            config.set(KEY_INSTALL_PATH, installPath.pathString)
            config.set(LEGACY_KEY_STEAM_PATH, installPath.pathString)
        }

        private fun envPath(name: String): Path? {
            val value = System.getenv(name)?.trim().orEmpty()
            if (value.isBlank()) return null
            return Paths.get(value).normalize()
        }

        private fun isSteamRoot(path: Path): Boolean {
            return path.resolve("steamapps").exists()
        }

        private fun readLibraryFolders(candidates: Set<Path>): List<Path> {
            val result = mutableListOf<Path>()
            candidates.forEach { steamRoot ->
                val vdf = steamRoot.resolve("steamapps/libraryfolders.vdf")
                if (!vdf.exists()) return@forEach
                runCatching {
                    val text = vdf.readText()
                    Regex("\"path\"\\s+\"([^\"]+)\"")
                        .findAll(text)
                        .map { it.groupValues[1].replace("\\\\", "\\") }
                        .map { Paths.get(it).normalize() }
                        .forEach { result.add(it.resolve("Steam").takeIf { p -> isSteamRoot(p) } ?: it) }
                }
            }
            return result
        }

        private fun findSaltPlayerDirFromSelectedFile(selectedFile: Path): Path? {
            var current: Path? = if (selectedFile.toFile().isDirectory) selectedFile else selectedFile.parent
            while (current != null) {
                if (current.fileName?.toString().equals(SALT_PLAYER_FOLDER, ignoreCase = true)) {
                    return current
                }
                current = current.parent
            }
            return null
        }
    }
}
