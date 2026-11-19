using System.Diagnostics;
using Microsoft.Extensions.Logging;
using Microsoft.Win32;

namespace PcStats.Agent.Sensors;

/// <summary>
/// Driver-free CPU readings from Windows performance counters, used when LibreHardwareMonitor can't read MSRs
/// (no PawnIO driver): effective clock (like Task Manager), package power from the RAPL "Energy Meter",
/// and the ACPI thermal-zone temperature. None of these need elevation.
/// </summary>
public sealed class WindowsCpuCounters : IDisposable
{
    private readonly ILogger _log;
    private Lazy<PerformanceCounter?> _perf;
    private Lazy<PerformanceCounter?> _power;
    private Lazy<PerformanceCounter?> _thermal;
    private readonly Lazy<double> _baseMhz;

    public WindowsCpuCounters(ILogger log)
    {
        _log = log;
        _baseMhz = new Lazy<double>(ReadBaseMhz);
        _perf = new Lazy<PerformanceCounter?>(() => Open("Processor Information", "% Processor Performance", "_Total"));
        _power = new Lazy<PerformanceCounter?>(() => Open("Energy Meter", "Power", FindInstance("Energy Meter", i => i.EndsWith("_PKG", StringComparison.OrdinalIgnoreCase))));
        _thermal = new Lazy<PerformanceCounter?>(() => Open("Thermal Zone Information", "High Precision Temperature", FindInstance("Thermal Zone Information", _ => true)));
    }

    public long? ClockMhz()
    {
        var pct = Next(ref _perf);
        return pct is > 0 && _baseMhz.Value > 0 ? (long)Math.Round(_baseMhz.Value * pct.Value / 100.0) : null;
    }

    /// <summary>CPU package power in watts (counter reports milliwatts).</summary>
    public double? PackagePowerW()
    {
        var mw = Next(ref _power);
        return mw is > 0 ? Math.Round(mw.Value / 1000.0, 1) : null;
    }

    /// <summary>ACPI thermal zone temperature in °C (counter reports tenths of a kelvin); null if implausible.</summary>
    public double? ThermalZoneC()
    {
        var dk = Next(ref _thermal);
        if (dk is not > 0) return null;
        var c = dk.Value / 10.0 - 273.15;
        return c is > 15 and < 115 ? Math.Round(c, 1) : null;
    }

    private float? Next(ref Lazy<PerformanceCounter?> lazy)
    {
        var counter = lazy.Value;
        if (counter is null) return null;
        try
        {
            return counter.NextValue();
        }
        catch (Exception ex)
        {
            _log.LogDebug(ex, "Reading counter {Category}\\{Counter} failed", counter.CategoryName, counter.CounterName);
            counter.Dispose();
            lazy = new Lazy<PerformanceCounter?>(() => null);
            return null;
        }
    }

    private PerformanceCounter? Open(string category, string counter, string? instance)
    {
        if (instance is null) return null;
        try
        {
            var c = new PerformanceCounter(category, counter, instance, readOnly: true);
            c.NextValue(); // Rate counters return 0 on the first read; prime them.
            _log.LogInformation("Using Windows counter {Category}\\{Counter}({Instance})", category, counter, instance);
            return c;
        }
        catch (Exception ex)
        {
            _log.LogInformation("Windows counter {Category}\\{Counter} unavailable: {Message}", category, counter, ex.Message);
            return null;
        }
    }

    private static string? FindInstance(string category, Func<string, bool> match)
    {
        try
        {
            if (!PerformanceCounterCategory.Exists(category)) return null;
            return new PerformanceCounterCategory(category).GetInstanceNames()
                .Where(i => !i.Equals("_Total", StringComparison.OrdinalIgnoreCase))
                .OrderBy(i => i, StringComparer.OrdinalIgnoreCase)
                .FirstOrDefault(match);
        }
        catch
        {
            return null;
        }
    }

    private static double ReadBaseMhz()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"HARDWARE\DESCRIPTION\System\CentralProcessor\0");
            return Convert.ToDouble(key?.GetValue("~MHz") ?? 0);
        }
        catch
        {
            return 0;
        }
    }

    public void Dispose()
    {
        foreach (var l in new[] { _perf, _power, _thermal })
        {
            if (l.IsValueCreated) l.Value?.Dispose();
        }
    }
}
