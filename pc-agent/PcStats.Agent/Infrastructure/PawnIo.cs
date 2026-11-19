using System.Diagnostics;
using Microsoft.Win32;

namespace PcStats.Agent.Infrastructure;

/// <summary>
/// LibreHardwareMonitor reads CPU MSRs (temperature, clocks, package power, voltage) through the PawnIO driver,
/// which must be installed separately.
/// </summary>
public static class PawnIo
{
    public const string WingetId = "namazso.PawnIO";

    public static bool IsInstalled
    {
        get
        {
            try
            {
                using var svc = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\PawnIO");
                if (svc is not null) return true;
                using var uninstall = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\PawnIO");
                return uninstall is not null;
            }
            catch
            {
                return false;
            }
        }
    }

    /// <summary>Opens a console window running the winget install; returns false if winget could not start.</summary>
    public static bool StartInstall()
    {
        try
        {
            Process.Start(new ProcessStartInfo("cmd.exe",
                $"/c winget install -e --id {WingetId} --accept-source-agreements --accept-package-agreements & pause")
            {
                UseShellExecute = true,
            });
            return true;
        }
        catch
        {
            return false;
        }
    }
}
