using Microsoft.Extensions.Logging;
using PcStats.Agent.Fps;
using PcStats.Agent.Protocol;

namespace PcStats.Agent.Sensors;

/// <summary>Background thread that refreshes sensors every ~500 ms and caches the latest snapshot.</summary>
public sealed class Sampler : IDisposable
{
    private static readonly TimeSpan Period = TimeSpan.FromMilliseconds(500);

    private readonly ILogger _log;
    private readonly PresentMonRunner? _fps;
    private readonly HardwareSensorReader _reader;
    private readonly NetworkRateTracker _net = new();
    private readonly DiskReader _disks;
    private readonly WindowsCpuCounters _cpuCounters;
    private readonly Games.GameCatalog? _games;
    private readonly Games.GameLogoService? _logos;
    private readonly Fonts.FontService? _fonts;
    private int _diskTick;
    private List<DiskStats> _lastDisks = [];
    private readonly CancellationTokenSource _cts = new();
    private readonly ManualResetEventSlim _firstSample = new();
    private Thread? _thread;
    private volatile Snapshot _latest = new() { Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() };

    public string? CpuName { get; private set; }
    public string? GpuName { get; private set; }

    public Sampler(ILogger log, PresentMonRunner? fps, Games.GameCatalog? games = null, Games.GameLogoService? logos = null,
        Fonts.FontService? fonts = null)
    {
        _log = log;
        _fps = fps;
        _games = games;
        _logos = logos;
        _fonts = fonts;
        _reader = new HardwareSensorReader(log);
        _disks = new DiskReader(log);
        _cpuCounters = new WindowsCpuCounters(log);
    }

    public Snapshot Latest => _latest;

    public void Start()
    {
        _thread = new Thread(Run) { IsBackground = true, Name = "Sensor sampler" };
        _thread.Start();
    }

    /// <summary>Blocks until the first sample (with hardware names) is available, or the timeout elapses.</summary>
    public bool WaitForFirstSample(TimeSpan timeout) => _firstSample.Wait(timeout);

    private void Run()
    {
        while (!_cts.IsCancellationRequested)
        {
            var started = DateTime.UtcNow;
            try
            {
                _latest = Sample();
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Sampling failed");
            }
            _firstSample.Set();

            var wait = Period - (DateTime.UtcNow - started);
            if (wait > TimeSpan.Zero) _cts.Token.WaitHandle.WaitOne(wait);
        }
    }

    private Snapshot Sample()
    {
        var readings = _reader.Read();
        var mapped = SnapshotMapper.Map(readings, HardwareSensorReader.GetGlobalMemoryStatus());
        CpuName ??= mapped.CpuName;
        GpuName ??= mapped.GpuName;
        var net = _net.Sample();

        // Free space changes slowly; query drives every ~5 s.
        if (_diskTick++ % 10 == 0) _lastDisks = _disks.Read(mapped.StorageTemps);

        var cpu = mapped.Cpu;
        if (cpu.ClockMhz is null || cpu.PowerW is null || cpu.Temp is null)
        {
            // Without the PawnIO driver LHM can't read these; fall back to Windows' own counters.
            cpu = new CpuStats
            {
                Usage = cpu.Usage,
                Temp = cpu.Temp ?? _cpuCounters.ThermalZoneC(),
                TempLimit = cpu.TempLimit,
                ClockMhz = cpu.ClockMhz ?? _cpuCounters.ClockMhz(),
                PowerW = cpu.PowerW ?? _cpuCounters.PackagePowerW(),
                Voltage = cpu.Voltage, FanRpm = cpu.FanRpm, CoreLoads = cpu.CoreLoads,
            };
        }

        FpsStats? fps = null;
        try
        {
            fps = _fps?.GetForegroundFps();
        }
        catch (Exception ex)
        {
            _log.LogDebug(ex, "FPS lookup failed");
        }

        if (fps is not null && _games is not null)
        {
            try
            {
                var (path, title) = Infrastructure.NativeMethods.GetForegroundWindowInfo();
                if (_games.Identify(fps.App, path, title) is { } game)
                    fps = fps with { Game = game.Name, Logo = _logos?.GetLogoUrl(game), Font = _fonts?.Resolve(game.FpsFonts), Color = game.FpsColor };
            }
            catch (Exception ex)
            {
                _log.LogDebug(ex, "Game lookup failed");
            }
        }

        return new Snapshot
        {
            Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Cpu = cpu,
            Gpu = mapped.Gpu,
            Ram = mapped.Ram,
            Net = new NetStats
            {
                UpBps = net.UpBps, DownBps = net.DownBps, Adapter = net.Adapter,
                SentBytes = net.SentBytes, ReceivedBytes = net.ReceivedBytes,
            },
            Fans = mapped.Fans,
            Disks = _lastDisks,
            Fps = fps,
        };
    }

    public void Dispose()
    {
        _cts.Cancel();
        _thread?.Join(TimeSpan.FromSeconds(3));
        _reader.Dispose();
        _cpuCounters.Dispose();
    }
}
