#!/system/bin/sh

# Harmless interaction test for persistent HOME and prompt-aware input replay.
config_file="${HOME:-.}/shelldeck-memory-test.txt"
saved_name=""
if [ -r "$config_file" ]; then
    IFS= read -r saved_name < "$config_file"
fi

echo "=== Shell Deck 输入记忆测试 ==="
echo "Android $(getprop ro.build.version.release) · $(id -un)"
if [ -n "$saved_name" ]; then
    echo "已读取上次保存的名称：$saved_name"
else
    echo "当前没有已保存的名称"
fi

printf "第 1 步：输入测试名称（直接回车沿用上次值）："
IFS= read -r name
if [ -z "$name" ]; then
    name="$saved_name"
fi
if [ -z "$name" ]; then
    name="未命名"
fi
printf '%s\n' "$name" > "$config_file"

echo "正在处理第 1 步，请等待 2 秒……"
sleep 2
printf "第 2 步：输入任意测试参数："
IFS= read -r parameter

echo "正在处理第 2 步，请等待 2 秒……"
sleep 2
printf "第 3 步：直接按回车，测试空输入："
IFS= read -r blank_value

echo ""
echo "=== 测试结果 ==="
echo "名称：$name"
echo "参数：$parameter"
if [ -z "$blank_value" ]; then
    echo "空输入：通过"
else
    echo "空输入：收到非空内容 '$blank_value'"
fi
echo "配置文件：$config_file"
echo "测试完成，不会启动后台进程。"
