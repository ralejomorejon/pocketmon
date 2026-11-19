using Microsoft.Extensions.Logging;
using PcStats.Agent.Config;
using PcStats.Agent.Fps;
using PcStats.Agent.Infrastructure;
using PcStats.Agent.Net;
using PcStats.Agent.Pairing;
using PcStats.Agent.Sensors;
using PcStats.Agent.Server;
using PcStats.Agent.Usb;

namespace PcStats.Agent;

/// <summary>Owns and wires every agent component; shared by tray and console modes.</summary>
public sealed class AgentHost : IAsyncDisposable
{
    private readonly ILoggerFactory _loggerFactory;
    private readonly ILogger _log;

    public SettingsStore Settings { get; }
    public PairingService Pairing { get; }
    public PresentMonRunner PresentMon { get; }
    public Sampler Sampler { get; }
    public StatsServer Server { get; }
    public DiscoveryBeacon Discovery { get; }
    public UsbHelper Usb { get; }

    public AgentHost(ILoggerFactory loggerFactory)
    {
        _loggerFactory = loggerFactory;
        _log = loggerFactory.CreateLogger("Agent");
        Settings = new SettingsStore();
        Pairing = new PairingService(Settings);
        PresentMon = new PresentMonRunner(loggerFactory.CreateLogger("PresentMon"));
        Sampler = new Sampler(loggerFactory.CreateLogger("Sensors"), PresentMon);
        Server = new StatsServer(loggerFactory, Settings, Pairing, Sampler);
        Discovery = new DiscoveryBeacon(loggerFactory.CreateLogger("Discovery"), Settings.PcId);
        Usb = new UsbHelper(loggerFactory.CreateLogger("Usb"));

        Pairing.CodeChanged += code => _log.LogInformation("Pairing code: {Code}", code);
    }

    public async Task StartAsync()
    {
        _log.LogInformation("PocketMon Agent starting (pcId {PcId}, elevated: {Elevated})", Settings.PcId, Elevation.IsElevated);
        _log.LogInformation("Pairing code: {Code}", Pairing.CurrentCode);
        _log.LogInformation("LAN IPs: {Ips}", string.Join(", ", LanAddresses.GetIpv4Interfaces().Select(i => i.Address)));
        if (!PawnIo.IsInstalled)
        {
            _log.LogWarning("PawnIO driver is not installed: CPU temperature, power and voltage will be unavailable. " +
                            "Install it with 'winget install {Id}' (or from the tray menu) and restart the agent", PawnIo.WingetId);
        }

        try
        {
            FirewallManager.EnsureRules(_loggerFactory.CreateLogger("Firewall"));
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "Firewall setup failed");
        }

        PresentMon.Start();
        Sampler.Start();
        await Server.StartAsync();
        Discovery.Start();
        Usb.Start();
    }

    public async ValueTask DisposeAsync()
    {
        _log.LogInformation("PocketMon Agent stopping");
        Usb.Dispose();
        Discovery.Dispose();
        await Server.DisposeAsync();
        Sampler.Dispose();
        PresentMon.Dispose();
        Pairing.Dispose();
    }
}
