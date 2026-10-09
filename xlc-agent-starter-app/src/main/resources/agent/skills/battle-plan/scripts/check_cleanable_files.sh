#!/bin/bash

# check_cleanable_files.sh
# 脚本功能：列举 macOS 系统中常见的垃圾文件和可清理内容，并显示占用空间大小。
# 注意：此脚本仅进行扫描和列举，不会删除任何文件。

echo "============================================================"
echo "               macOS 系统可清理垃圾文件扫描                 "
echo "============================================================"
echo "正在扫描，请稍候..."
echo ""

# 定义颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 函数：检查并打印目录大小
check_dir_size() {
    local name="$1"
    local path="$2"
    local desc="$3"

    if [ -d "$path" ]; then
        # 使用 du -sh 计算大小，并提取数值
        # 2>/dev/null 屏蔽权限错误
        size=$(du -sh "$path" 2>/dev/null | cut -f1)
        
        # 如果目录为空或无法读取大小，可能显示为 0B 或空
        if [ -n "$size" ]; then
            echo -e "${YELLOW}[$name]${NC}"
            echo -e "  路径: $path"
            echo -e "  大小: ${RED}$size${NC}"
            echo -e "  说明: $desc"
            echo "------------------------------------------------------------"
        fi
    fi
}

# 1. 用户缓存
check_dir_size "用户缓存" "$HOME/Library/Caches" "应用程序产生的临时文件，通常可以安全清理（可能会导致应用重新加载数据变慢）。"

# 2. 系统日志
check_dir_size "用户日志" "$HOME/Library/Logs" "应用程序的日志文件，如果不需要排查问题，通常可以清理。"
check_dir_size "系统日志" "/private/var/log" "系统运行日志，通常由系统自动管理，但积压过多时可清理旧日志。"

# 3. 废纸篓
check_dir_size "废纸篓" "$HOME/.Trash" "已删除但未清空的文件。"

# 4. Xcode 开发垃圾 (如果存在)
check_dir_size "Xcode DerivedData" "$HOME/Library/Developer/Xcode/DerivedData" "Xcode 编译产生的中间文件和索引，删除后下次编译会重新生成（可解决很多 Xcode 报错问题）。"
check_dir_size "Xcode iOS DeviceSupport" "$HOME/Library/Developer/Xcode/iOS DeviceSupport" "连接过的旧 iOS 设备支持文件，如果不再调试旧版本 iOS，可以清理。"
check_dir_size "Xcode Archives" "$HOME/Library/Developer/Xcode/Archives" "打包发布的 App 归档，如果确认不再需要旧版本的包，可以清理。"

# 5. 浏览器缓存 (部分示例)
check_dir_size "Chrome 缓存" "$HOME/Library/Caches/Google/Chrome" "Chrome 浏览器的缓存文件。"
# Firefox
check_dir_size "Firefox 缓存" "$HOME/Library/Caches/Firefox" "Firefox 浏览器的缓存文件。"

# 6. 包管理器缓存
# Homebrew
if command -v brew &> /dev/null; then
    brew_cache=$(brew --cache)
    check_dir_size "Homebrew 缓存" "$brew_cache" "Homebrew 下载的安装包缓存，可通过 'brew cleanup' 清理。"
fi

# 7. 语言环境缓存/依赖
check_dir_size "Yarn 缓存" "$HOME/Library/Caches/Yarn" "Yarn 包管理器缓存。"
check_dir_size "npm 缓存" "$HOME/.npm" "npm 包管理器缓存。"
check_dir_size "Maven 缓存" "$HOME/.m2/repository" "Maven 仓库，虽然不是垃圾，但如果很久不用，可能占用大量空间。"
check_dir_size "Gradle 缓存" "$HOME/.gradle/caches" "Gradle 构建缓存。"
check_dir_size "CocoaPods 缓存" "$HOME/Library/Caches/CocoaPods" "CocoaPods 依赖缓存。"

# 8. Docker (如果运行)
if command -v docker &> /dev/null; then
    echo -e "${YELLOW}[Docker 未使用资源]${NC}"
    echo -e "  说明: 停止的容器、未使用的镜像和网络。"
    echo -e "  建议执行命令: ${GREEN}docker system df${NC} 查看详情"
    # docker system df 可能需要 docker 正在运行
    if docker info &> /dev/null; then
        docker system df
    else
        echo "  (Docker 服务未运行，无法获取大小)"
    fi
    echo "------------------------------------------------------------"
fi

# 9. 下载文件夹 (提醒)
check_dir_size "下载文件夹" "$HOME/Downloads" "下载的文件，通常包含很多不再需要的安装包和临时文件。"

echo ""
echo "============================================================"
echo "建议清理方式："
echo "1. 使用 'rm -rf <路径>' 删除特定目录内容（请务必小心确认路径）。"
echo "2. 对于 Homebrew，使用 'brew cleanup'。"
echo "3. 对于 Docker，使用 'docker system prune'。"
echo "4. 对于 Xcode，可以直接删除 DerivedData 目录。"
echo "5. 推荐使用专门的清理工具（如 CleanMyMac 或腾讯柠檬清理）进行更安全的清理。"
echo "============================================================"
