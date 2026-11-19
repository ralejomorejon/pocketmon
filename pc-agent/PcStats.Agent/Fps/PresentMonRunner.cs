using System.Diagnostics;
using Microsoft.Extensions.Logging;
using PcStats.Agent.Infrastructure;

namespace PcStats.Agent.Fps;

/// <summary>Runs the PresentMon console exe, feeds its CSV into an <see cref="FpsTracker"/>, restarts with backoff.</summary>
public sealed class PresentMonRunner : IDisposable
{
    public const string SessionName = "PcStatsAgent";

    private static readonly string[] Arguments =
    [
        "--output_stdout", "--stop_existing_session", "--session_name", SessionName, "--v2_metrics", "--no_console_stats",
    ];

    private readonly ILogger _log;
    private readonly CancellationTokenSource _cts = new();
    private readonly KillOnCloseJob? _job;
    private Process? _process;
    private Task? _loop;

    public FpsTracker Tracker { get; } = new();
    public string? ExePath { get; }
    public string Status { get; private set; } = "not started";

    public PresentMonRunner(ILogger log)
    {
        _log = log;
        ExePath = FindExe(AppContext.BaseDirectory);
        try
        {
            _job = new KillOnCloseJob();
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "Could not create job object; PresentMon will be killed on normal exit only");
        }
    }

    public static string? FindExe(string baseDir)
    {
        foreach (var dir in new[] { baseDir, Path.Combine(baseDir, "tools") })
        {
            if (!Directory.Exists(dir)) continue;
            var match = Directory.GetFiles(dir, "PresentMon*.exe")
                .OrderByDescending(f => Path.GetFileName(f), StringComparer.OrdinalIgnoreCase)
                .FirstOrDefault();
            if (match is not null) return match;
        }
        return null;
    }

    public void Start()
    {
        if (ExePath is null)
        {
            Status = "PresentMon exe not found (run scripts\\get-presentmon.ps1)";
            _log.LogWarning("PresentMon*.exe not found next to the agent or in tools\\; FPS will be null");
            return;
        }
        if (!Elevation.IsElevated)
            _log.LogWarning("Agent is not elevated; PresentMon will likely fail with access denied and FPS will be null");
        _loop = Task.Run(() => RunLoopAsync(_cts.Token));
    }

    /// <summary>FPS for the current foreground window's process.</summary>
    public Protocol.FpsStats? GetForegroundFps()
    {
        var pid = NativeMethods.GetForegroundProcessId();
        if (pid is null) return null;
        var reading = Tracker.GetFps(pid.Value, Environment.TickCount64);
        if (reading is null) return null;

        var app = reading.Value.App;
        if (string.IsNullOrEmpty(app) || app.StartsWith('<'))
        {
            try
            {
                using var p = Process.GetProcessById(pid.Value);
                app = p.ProcessName + ".exe";
            }
            catch
            {
                app = "unknown";
            }
        }
        return new Protocol.FpsStats(app, reading.Value.Fps);
    }

    private async Task RunLoopAsync(CancellationToken ct)
    {
        var backoff = TimeSpan.FromSeconds(2);
        var failures = 0;
        while (!ct.IsCancellationRequested)
        {
            var started = Stopwatch.StartNew();
            try
            {
                await RunOnceAsync(ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "PresentMon run failed");
            }

            if (ct.IsCancellationRequested) break;
            if (started.Elapsed > TimeSpan.FromSeconds(30))
            {
                backoff = TimeSpan.FromSeconds(2);
                failures = 0;
            }
            failures++;
            if (failures <= 3 || failures % 20 == 0)
                _log.LogInformation("PresentMon exited; restarting in {Delay}s", backoff.TotalSeconds);
            try
            {
                await Task.Delay(backoff, ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            backoff = TimeSpan.FromSeconds(Math.Min(backoff.TotalSeconds * 2, 60));
        }
    }

    private async Task RunOnceAsync(CancellationToken ct)
    {
        var psi = new ProcessStartInfo(ExePath!)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            WorkingDirectory = Path.GetDirectoryName(ExePath!)!,
        };
        foreach (var a in Arguments) psi.ArgumentList.Add(a);

        Tracker.Reset();
        using var process = new Process { StartInfo = psi };
        var lastError = "";
        process.ErrorDataReceived += (_, e) =>
        {
            if (string.IsNullOrWhiteSpace(e.Data)) return;
            lastError = e.Data.Trim();
            _log.LogDebug("PresentMon: {Line}", lastError);
        };

        process.Start();
        _process = process;
        try
        {
            _job?.Add(process);
        }
        catch (Exception ex)
        {
            _log.LogDebug(ex, "Assigning PresentMon to job failed");
        }
        process.BeginErrorReadLine();
        Status = "running";
        _log.LogInformation("Started PresentMon (pid {Pid})", process.Id);

        using var reg = ct.Register(() => KillQuietly(process));
        while (await process.StandardOutput.ReadLineAsync(ct) is { } line)
        {
            Tracker.ProcessLine(line, Environment.TickCount64);
        }
        await process.WaitForExitAsync(ct);
        Status = $"exited ({process.ExitCode}) {lastError}".Trim();
        _log.LogInformation("PresentMon exited with code {Code}: {Error}", process.ExitCode, lastError);
        _process = null;
    }

    private static void KillQuietly(Process p)
    {
        try
        {
            if (!p.HasExited) p.Kill(entireProcessTree: true);
        }
        catch
        {
            // Already gone.
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        if (_process is { } p) KillQuietly(p);
        try
        {
            _loop?.Wait(TimeSpan.FromSeconds(3));
        }
        catch
        {
            // Loop faults are already logged.
        }
        StopEtwSession();
        _job?.Dispose();
    }

    private void StopEtwSession()
    {
        if (ExePath is null || !Elevation.IsElevated) return;
        try
        {
            var psi = new ProcessStartInfo(ExePath)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            psi.ArgumentList.Add("--terminate_existing_session");
            psi.ArgumentList.Add("--session_name");
            psi.ArgumentList.Add(SessionName);
            using var p = Process.Start(psi);
            if (p is not null && !p.WaitForExit(3000)) KillQuietly(p);
        }
        catch (Exception ex)
        {
            _log.LogDebug(ex, "Stopping PresentMon ETW session failed");
        }
    }
}
