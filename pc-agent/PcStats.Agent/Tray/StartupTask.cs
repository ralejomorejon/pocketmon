using PcStats.Agent.Infrastructure;

namespace PcStats.Agent.Tray;

/// <summary>Start-with-Windows via a Task Scheduler logon task with highest privileges (no UAC prompt).</summary>
public static class StartupTask
{
    public const string TaskName = "PcStatsAgent";

    public static bool IsEnabled() =>
        ProcessRunner.Run("schtasks", ["/Query", "/TN", TaskName]).ExitCode == 0;

    public static ProcessRunner.Result Enable(string exePath) =>
        ProcessRunner.Run("schtasks",
            ["/Create", "/TN", TaskName, "/TR", $"\"{exePath}\"", "/SC", "ONLOGON", "/RL", "HIGHEST", "/F"]);

    public static ProcessRunner.Result Disable() =>
        ProcessRunner.Run("schtasks", ["/Delete", "/TN", TaskName, "/F"]);
}
