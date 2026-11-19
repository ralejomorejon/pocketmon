using Microsoft.Extensions.Logging;
using PcStats.Agent.Infrastructure;
using PcStats.Agent.Protocol;

namespace PcStats.Agent.Usb;

/// <summary>Every 3 s, runs <c>adb reverse tcp:28555 tcp:28555</c> for newly attached devices.</summary>
public sealed class UsbHelper : IDisposable
{
    private static readonly TimeSpan Period = TimeSpan.FromSeconds(3);

    private readonly ILogger _log;
    private readonly CancellationTokenSource _cts = new();
    private readonly HashSet<string> _reversed = new();
    private Task? _loop;

    public UsbHelper(ILogger log) => _log = log;

    public string? AdbPath { get; private set; }
    public int DeviceCount { get; private set; }

    public void Start() => _loop = Task.Run(() => RunAsync(_cts.Token));

    public static string? FindAdb()
    {
        var candidates = new List<string>();
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
            candidates.Add(Path.Combine(dir.Trim('"'), "adb.exe"));

        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        candidates.Add(Path.Combine(local, "Android", "Sdk", "platform-tools", "adb.exe"));
        foreach (var env in new[] { "ANDROID_HOME", "ANDROID_SDK_ROOT" })
        {
            var root = Environment.GetEnvironmentVariable(env);
            if (!string.IsNullOrWhiteSpace(root)) candidates.Add(Path.Combine(root, "platform-tools", "adb.exe"));
        }

        return candidates.FirstOrDefault(p =>
        {
            try
            {
                return File.Exists(p);
            }
            catch
            {
                return false;
            }
        });
    }

    /// <summary>Parses <c>adb devices</c> output into serials of devices in the "device" state.</summary>
    public static List<string> ParseDevices(string output) =>
        output.Split('\n')
            .Select(l => l.Trim())
            .Where(l => l.Length > 0 && !l.StartsWith("List of devices", StringComparison.OrdinalIgnoreCase) && !l.StartsWith('*'))
            .Select(l => l.Split(['\t', ' '], StringSplitOptions.RemoveEmptyEntries))
            .Where(p => p.Length >= 2 && p[1] == "device")
            .Select(p => p[0])
            .ToList();

    private async Task RunAsync(CancellationToken ct)
    {
        using var timer = new PeriodicTimer(Period);
        do
        {
            try
            {
                Tick();
            }
            catch (Exception ex)
            {
                _log.LogDebug(ex, "USB helper tick failed");
            }
            try
            {
                if (!await timer.WaitForNextTickAsync(ct)) break;
            }
            catch (OperationCanceledException)
            {
                break;
            }
        } while (!ct.IsCancellationRequested);
    }

    private void Tick()
    {
        AdbPath ??= FindAdb();
        if (AdbPath is null) return;

        var devices = ProcessRunner.Run(AdbPath, ["devices"], TimeSpan.FromSeconds(10));
        if (devices.ExitCode != 0) return;
        var serials = ParseDevices(devices.Output);
        DeviceCount = serials.Count;

        _reversed.RemoveWhere(s => !serials.Contains(s));
        foreach (var serial in serials.Where(s => !_reversed.Contains(s)))
        {
            var port = $"tcp:{ProtocolConstants.WebSocketPort}";
            var r = ProcessRunner.Run(AdbPath, ["-s", serial, "reverse", port, port], TimeSpan.FromSeconds(10));
            if (r.ExitCode == 0)
            {
                _reversed.Add(serial);
                _log.LogInformation("adb reverse set up for USB device {Serial}", serial);
            }
            else
            {
                _log.LogWarning("adb reverse failed for {Serial}: {Output}", serial, r.Output.Trim());
            }
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        try
        {
            _loop?.Wait(TimeSpan.FromSeconds(2));
        }
        catch
        {
        }
    }
}
