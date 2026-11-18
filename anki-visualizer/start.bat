@echo off
chcp 65001 > nul
echo 正在启动 Anki 数据可视化 Web 界面...
python "%~dp0server.py"
pause
