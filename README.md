# WallpaperSwitcher 插件

用于替换 Steam 版 Salt Player for Windows 的壁纸文件：

- 目标文件：`Salt Player for Windows\app\resources\bg_wallpaper.jpg`
- 替换逻辑：将你提供的图片复制覆盖目标文件
- 恢复逻辑：恢复首次替换时自动生成的备份文件 `bg_wallpaper.original.backup.jpg`

## 构建

在仓库根目录执行：

```bash
./gradlew :wallpaper-plugin:plugin
```

会生成：

`%APPDATA%/Salt Player for Windows/workshop/plugins/WallpaperSwitcher-1.0.0.zip`

## 安装

将生成的 zip 放到 SPW 插件目录并在 SPW 中安装/启用。

## 配置项

- `选择 Salt Player 路径`：打开原生文件选择器，选择 `Salt Player for Windows` 目录内任意文件后自动识别安装目录
- `自动检测 Salt Player 路径`：扫描配置路径、常见安装目录和 Steam 库，自动定位本软件安装位置
- `选择新壁纸`：打开外置文件管理器选择 jpg，选择后自动替换生效
- `恢复默认壁纸`：恢复备份
