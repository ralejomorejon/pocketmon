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
    public Games.GameCatalog Games { get; }
    public Games.GameLogoService Logos { get; }
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
        Games = PcStats.Agent.Games.GameCatalog.Load(
            Path.Combine(AppContext.BaseDirectory, "games.json"),
            Path.Combine(SettingsStore.DefaultDirectory, "games.json"));
        Logos = new PcStats.Agent.Games.GameLogoService(loggerFactory.CreateLogger("Games"),
            Path.Combine(SettingsStore.DefaultDirectory, "logos"), Path.Combine(AppContext.BaseDirectory, "logos"));
        Sampler = new Sampler(loggerFactory.CreateLogger("Sensors"), PresentMon, Games, Logos);
        Server = new StatsServer(loggerFactory, Settings, Pairing, Sampler, Games, Logos);
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
        // Fetch logos for popular Steam games in the background so they're ready when a game starts.
        _ = Logos.PrefetchAsync(Games.Games, _prefetch.Token);
    }

    private readonly CancellationTokenSource _prefetch = new();

    public async ValueTask DisposeAsync()
    {
        _log.LogInformation("PocketMon Agent stopping");
        _prefetch.Cancel();
        Usb.Dispose();
        Discovery.Dispose();
        await Server.DisposeAsync();
        Sampler.Dispose();
        PresentMon.Dispose();
        Pairing.Dispose();
    }
}
