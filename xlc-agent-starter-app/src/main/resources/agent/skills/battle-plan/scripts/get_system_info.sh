#!/bin/bash

# 脚本名称: get_system_info.sh
# 描述: 获取 macOS 系统配置信息的脚本
# 作者: Trae AI

echo "================================================"
echo "           系统配置信息概览"
echo "================================================"

# 1. 主机名
echo "【主机信息】"
echo "  主机名    : $(hostname)"
echo "  用户名    : $(whoami)"
echo ""

# 2. 操作系统版本
echo "【操作系统】"
PRODUCT_NAME=$(sw_vers -productName)
PRODUCT_VERSION=$(sw_vers -productVersion)
BUILD_VERSION=$(sw_vers -buildVersion)
echo "  系统名称  : $PRODUCT_NAME"
echo "  系统版本  : $PRODUCT_VERSION (Build $BUILD_VERSION)"
# 获取内核版本
echo "  内核版本  : $(uname -r)"
echo ""

# 3. CPU 信息
echo "【CPU 信息】"
CPU_BRAND=$(sysctl -n machdep.cpu.brand_string)
PHY_CORES=$(sysctl -n hw.physicalcpu)
LOG_CORES=$(sysctl -n hw.logicalcpu)
echo "  型号      : $CPU_BRAND"
echo "  物理核心  : $PHY_CORES"
echo "  逻辑核心  : $LOG_CORES"
# 尝试获取架构 (e.g. x86_64 or arm64)
ARCH=$(uname -m)
echo "  架构      : $ARCH"
echo ""

# 4. 内存信息
echo "【内存信息】"
MEM_BYTES=$(sysctl -n hw.memsize)
MEM_GB=$(echo "scale=2; $MEM_BYTES / 1024 / 1024 / 1024" | bc)
echo "  总内存    : ${MEM_GB} GB"
echo ""

# 5. 磁盘使用情况 (根目录)
echo "【磁盘信息 (根目录)】"
# 使用 df -h 获取根目录信息，并格式化输出
df -h / | awk 'NR==2 {printf "  总容量    : %s\n  已用      : %s\n  可用      : %s\n  使用率    : %s\n", $2, $3, $4, $5}'
echo ""

# 6. 网络信息
echo "【网络信息】"
# 获取默认接口的 IP (通常是 en0 Wi-Fi 或 en1)
IP_ADDR=$(ipconfig getifaddr en0)
if [ -z "$IP_ADDR" ]; then
    IP_ADDR=$(ipconfig getifaddr en1)
fi

if [ -z "$IP_ADDR" ]; then
    echo "  IP 地址   : 未连接或无法获取"
else
    echo "  IP 地址   : $IP_ADDR"
fi
echo ""

echo "================================================"
echo "信息获取完成。"
