using System.Security.Principal;

namespace PcStats.Agent.Infrastructure;

public static class Elevation
{
    public static bool IsElevated { get; } = Check();

    private static bool Check()
    {
        try
        {
            using var identity = WindowsIdentity.GetCurrent();
            return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
        }
        catch
        {
            return false;
        }
    }
}
