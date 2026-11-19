using Microsoft.Extensions.Logging;
using PcStats.Agent.Infrastructure;
using PcStats.Agent.Protocol;

namespace PcStats.Agent.Net;

/// <summary>Ensures inbound firewall rules exist for the WebSocket and discovery ports (requires elevation).</summary>
public static class FirewallManager
{
    public const string RuleName = "PocketMon Agent";

    public static void EnsureRules(ILogger log)
    {
        if (!Elevation.IsElevated)
        {
            log.LogWarning("Not elevated: skipping firewall rule setup. Phones on Wi-Fi may be blocked by Windows Firewall");
            return;
        }

        var existing = ProcessRunner.Run("netsh", ["advfirewall", "firewall", "show", "rule", $"name={RuleName}", "verbose"]);
        var text = existing.ExitCode == 0 ? existing.Output : "";

        Ensure(log, text, "TCP", ProtocolConstants.WebSocketPort);
        Ensure(log, text, "UDP", ProtocolConstants.DiscoveryPort);
    }

    private static void Ensure(ILogger log, string showOutput, string protocol, int port)
    {
        if (HasRule(showOutput, protocol, port))
        {
            log.LogInformation("Firewall rule for {Protocol} {Port} already present", protocol, port);
            return;
        }

        var add = ProcessRunner.Run("netsh",
        [
            "advfirewall", "firewall", "add", "rule", $"name={RuleName}", "dir=in", "action=allow",
            $"protocol={protocol}", $"localport={port}", "profile=private,domain",
        ]);
        if (add.ExitCode == 0)
            log.LogInformation("Added firewall rule for {Protocol} {Port}", protocol, port);
        else
            log.LogWarning("Adding firewall rule for {Protocol} {Port} failed: {Output}", protocol, port, add.Output.Trim());
    }

    /// <summary>Checks netsh "show rule verbose" output for a rule block with the given protocol and local port.</summary>
    internal static bool HasRule(string showOutput, string protocol, int port)
    {
        var blocks = showOutput.Split(["\r\n\r\n", "\n\n"], StringSplitOptions.RemoveEmptyEntries);
        foreach (var block in blocks)
        {
            var lines = block.Split('\n').Select(l => l.Trim()).ToList();
            var hasProtocol = lines.Any(l => l.Contains(protocol, StringComparison.OrdinalIgnoreCase) && l.Contains(':'));
            var hasPort = lines.Any(l => l.Contains(':') && l.Split(':', 2)[1].Trim() == port.ToString());
            if (hasProtocol && hasPort) return true;
        }
        return false;
    }
}
