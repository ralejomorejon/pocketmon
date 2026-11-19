using System.ComponentModel;
using System.Diagnostics;
using Microsoft.Extensions.Logging;
using PcStats.Agent.Config;
using PcStats.Agent.Infrastructure;
using PcStats.Agent.Tray;

namespace PcStats.Agent;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        var console = args.Any(a => a.Equals("--console", StringComparison.OrdinalIgnoreCase));
        var skipElevationCheck = args.Any(a => a.Equals("--no-elevation-check", StringComparison.OrdinalIgnoreCase));

        if (!console && !skipElevationCheck && !Elevation.IsElevated && TryRelaunchElevated(args))
            return 0;

        if (console && !Console.IsOutputRedirected && !NativeMethods.AttachConsole(-1))
            NativeMethods.AllocConsole();

        using var mutex = new Mutex(true, @"Local\PcStatsAgent", out var isFirst);
        if (!isFirst)
        {
            const string msg = "PocketMon Agent is already running.";
            if (console) Console.Error.WriteLine(msg);
            else MessageBox.Show(msg, "PocketMon Agent", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return 1;
        }

        var fileLogger = new FileLoggerProvider(Path.Combine(SettingsStore.DefaultDirectory, "logs"));
        var loggerFactory = LoggerFactory.Create(b =>
        {
            b.SetMinimumLevel(LogLevel.Information);
            b.AddProvider(fileLogger);
            if (console)
            {
                b.AddSimpleConsole(o =>
                {
                    o.SingleLine = true;
                    o.TimestampFormat = "HH:mm:ss ";
                });
            }
        });
        var log = loggerFactory.CreateLogger("Program");
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
            log.LogCritical(e.ExceptionObject as Exception, "Unhandled exception");

        if (!Elevation.IsElevated)
            log.LogWarning("Not running elevated: some temperatures/power may be null; FPS and firewall setup are unavailable");

        var host = new AgentHost(loggerFactory);
        var exitCode = 0;
        try
        {
            host.StartAsync().GetAwaiter().GetResult();

            if (console)
            {
                using var exit = new ManualResetEventSlim();
                Console.CancelKeyPress += (_, e) =>
                {
                    e.Cancel = true;
                    exit.Set();
                };
                log.LogInformation("Running in console mode. Press Ctrl+C to exit. Log file: {File}", fileLogger.CurrentFile);
                exit.Wait();
            }
            else
            {
                ApplicationConfiguration.Initialize();
                Application.Run(new TrayContext(host, log));
            }
        }
        catch (Exception ex)
        {
            log.LogCritical(ex, "Agent failed to start (is port 28555 already in use?)");
            if (!console) MessageBox.Show($"PocketMon Agent failed to start:\n{ex.Message}", "PocketMon Agent");
            exitCode = 2;
        }
        finally
        {
            host.DisposeAsync().AsTask().GetAwaiter().GetResult();
            loggerFactory.Dispose();
        }
        return exitCode;
    }

    /// <summary>Restarts the agent with UAC elevation; returns false if the user declined or it failed.</summary>
    private static bool TryRelaunchElevated(string[] args)
    {
        var exe = Environment.ProcessPath;
        if (exe is null || Path.GetFileNameWithoutExtension(exe).Equals("dotnet", StringComparison.OrdinalIgnoreCase))
            return false;
        try
        {
            var psi = new ProcessStartInfo(exe) { UseShellExecute = true, Verb = "runas" };
            foreach (var a in args) psi.ArgumentList.Add(a);
            psi.ArgumentList.Add("--no-elevation-check");
            Process.Start(psi);
            return true;
        }
        catch (Win32Exception)
        {
            return false;
        }
    }
}
