using System.Text.RegularExpressions;
using PcStats.Agent.Protocol;

namespace PcStats.Agent.Sensors;

/// <summary>Pure mapping from a flat sensor list to the snapshot sections.</summary>
public static partial class SnapshotMapper
{
    // "Core #1" (AMD / older Intel) or "P-Core #1" / "E-Core #1" (Intel hybrid).
    [GeneratedRegex(@"^(?:[PE]-)?Core #\d+$", RegexOptions.IgnoreCase)]
    private static partial Regex CoreName { get; }

    // Per-core load: "CPU Core #1", "P-Core #1", optionally with " Thread #n" (SMT).
    [GeneratedRegex(@"^(?:CPU )?(?<core>(?:[PE]-)?Core #\d+)(?: Thread #\d+)?$", RegexOptions.IgnoreCase)]
    private static partial Regex CoreLoadName { get; }

    public sealed record Result(
        CpuStats Cpu,
        GpuStats Gpu,
        RamStats Ram,
        List<FanStats> Fans,
        string? CpuName,
        string? GpuName,
        IReadOnlyDictionary<string, double?>? StorageTemps = null);

    public static Result Map(IReadOnlyList<SensorReading> readings, MemoryInfo? memoryFallback = null)
    {
        var cpuSensors = SelectCpu(readings, out var cpuName);
        var gpuSensors = SelectGpu(readings, out var gpuName);

        return new Result(
            MapCpu(cpuSensors, CpuFan(readings)),
            MapGpu(gpuSensors),
            MapRam(readings, memoryFallback),
            MapFans(readings),
            cpuName,
            gpuName,
            MapStorageTemps(readings));
    }

    /// <summary>Storage temperature keyed by device model (LHM storage hardware name).</summary>
    private static Dictionary<string, double?> MapStorageTemps(IReadOnlyList<SensorReading> readings)
    {
        var result = new Dictionary<string, double?>(StringComparer.OrdinalIgnoreCase);
        foreach (var g in readings.Where(r => r.Hardware == HardwareKind.Storage).GroupBy(r => r.HardwareId))
        {
            var name = g.First().HardwareName.Trim();
            var temp = g.FirstOrDefault(r => r.Kind == SensorKind.Temperature && r.Value is > 0)?.Value;
            if (!result.TryGetValue(name, out var existing) || existing is null) result[name] = Round1(temp);
        }
        return result;
    }

    /// <summary>A motherboard/EC fan whose name mentions the CPU, else the first non-GPU fan.</summary>
    private static double? CpuFan(IReadOnlyList<SensorReading> readings)
    {
        var fans = readings.Where(r => r.Kind == SensorKind.Fan && r.Value is > 0
                                       && r.Hardware is HardwareKind.Motherboard or HardwareKind.Controller or HardwareKind.Cpu)
            .ToList();
        return (fans.FirstOrDefault(r => r.Name.Contains("CPU", StringComparison.OrdinalIgnoreCase)) ?? fans.FirstOrDefault())?.Value;
    }

    private static List<SensorReading> SelectCpu(IReadOnlyList<SensorReading> readings, out string? name)
    {
        var first = readings.FirstOrDefault(r => r.Hardware == HardwareKind.Cpu);
        name = first?.HardwareName;
        if (first is null) return [];
        return readings.Where(r => r.Hardware == HardwareKind.Cpu && r.HardwareId == first.HardwareId).ToList();
    }

    private static List<SensorReading> SelectGpu(IReadOnlyList<SensorReading> readings, out string? name)
    {
        // Discrete vendors first, then the device with the most VRAM (a dGPU beats an APU of the same vendor).
        var best = readings
            .Where(r => IsGpu(r.Hardware))
            .GroupBy(r => r.HardwareId)
            .OrderBy(g => GpuRank(g.First().Hardware))
            .ThenByDescending(g => Find(g.ToList(), SensorKind.SmallData, "GPU Memory Total") ?? 0)
            .FirstOrDefault();
        name = best?.First().HardwareName;
        return best?.ToList() ?? [];
    }

    private static bool IsGpu(HardwareKind kind) =>
        kind is HardwareKind.GpuNvidia or HardwareKind.GpuAmd or HardwareKind.GpuIntel;

    private static int GpuRank(HardwareKind kind) => kind switch
    {
        HardwareKind.GpuNvidia => 0,
        HardwareKind.GpuAmd => 1,
        _ => 2,
    };

    private static CpuStats MapCpu(List<SensorReading> s, double? fanRpm)
    {
        var temp = Find(s, SensorKind.Temperature, "CPU Package")
            ?? Find(s, SensorKind.Temperature, "Core (Tctl/Tdie)")
            ?? Find(s, SensorKind.Temperature, "Core Max")
            ?? Find(s, SensorKind.Temperature, "Core Average")
            ?? Average(s.Where(r => r.Kind == SensorKind.Temperature && CoreName.IsMatch(r.Name)));

        var coreClocks = s.Where(r => r.Kind == SensorKind.Clock && CoreName.IsMatch(r.Name) && r.Value is > 0);

        // LHM reports 0 W package power when it cannot read the RAPL MSRs (not elevated).
        var power = Find(s, SensorKind.Power, "Package") ?? Find(s, SensorKind.Power, "CPU Package");

        var voltage = Find(s, SensorKind.Voltage, "CPU Core")
            ?? Find(s, SensorKind.Voltage, "Core (SVI2 TFN)")
            ?? Find(s, SensorKind.Voltage, "Core (SVI3 TFN)")
            ?? Average(s.Where(r => r.Kind == SensorKind.Voltage && CoreName.IsMatch(r.Name) && r.Value is > 0));

        return new CpuStats
        {
            Usage = Round1(Find(s, SensorKind.Load, "CPU Total")),
            Temp = Round1(temp),
            TempLimit = TjMax(s),
            ClockMhz = RoundLong(Average(coreClocks)),
            PowerW = power > 0 ? Round1(power) : null,
            Voltage = voltage > 0 ? Math.Round(voltage.Value, 3) : null,
            FanRpm = RoundLong(fanRpm),
            CoreLoads = CoreLoads(s),
        };
    }

    /// <summary>Intel exposes "Core #n Distance to TjMax"; temp + distance gives the throttle limit.</summary>
    private static double? TjMax(List<SensorReading> s)
    {
        foreach (var d in s.Where(r => r.Kind == SensorKind.Temperature && r.Value.HasValue
                                       && r.Name.EndsWith(" Distance to TjMax", StringComparison.OrdinalIgnoreCase)))
        {
            var core = d.Name[..^" Distance to TjMax".Length];
            var t = Find(s, SensorKind.Temperature, core);
            if (t is > 0) return Math.Round(t.Value + d.Value!.Value);
        }
        return null;
    }

    /// <summary>One load value per physical core; SMT thread loads are averaged into their core.</summary>
    private static List<double> CoreLoads(List<SensorReading> s)
    {
        var order = new List<string>();
        var byCore = new Dictionary<string, List<double>>(StringComparer.OrdinalIgnoreCase);
        var aggregate = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var r in s.Where(r => r.Kind == SensorKind.Load && r.Value.HasValue))
        {
            var m = CoreLoadName.Match(r.Name);
            if (!m.Success) continue;
            var core = m.Groups["core"].Value;
            var isThread = r.Name.Contains("Thread", StringComparison.OrdinalIgnoreCase);
            if (!byCore.TryGetValue(core, out var list))
            {
                byCore[core] = list = [];
                order.Add(core);
            }
            // Prefer a per-core aggregate sensor over its thread sensors when both exist.
            if (!isThread)
            {
                if (aggregate.Add(core)) list.Clear();
                list.Add(r.Value!.Value);
            }
            else if (!aggregate.Contains(core))
            {
                list.Add(r.Value!.Value);
            }
        }
        return order.Select(c => Math.Round(Math.Clamp(byCore[c].Average(), 0, 100), 1)).ToList();
    }

    private static GpuStats MapGpu(List<SensorReading> s)
    {
        var power = Find(s, SensorKind.Power, "GPU Package")
            ?? Find(s, SensorKind.Power, "GPU Power")
            ?? s.FirstOrDefault(r => r.Kind == SensorKind.Power && r.Value.HasValue)?.Value;
        var fan = s.FirstOrDefault(r => r.Kind == SensorKind.Fan && r.Value is > 0)?.Value;
        var voltage = Find(s, SensorKind.Voltage, "GPU Core") ?? s.FirstOrDefault(r => r.Kind == SensorKind.Voltage && r.Value is > 0)?.Value;

        return new GpuStats
        {
            Usage = Round1(Find(s, SensorKind.Load, "GPU Core")),
            Temp = Round1(Find(s, SensorKind.Temperature, "GPU Core") ?? Find(s, SensorKind.Temperature, "GPU Hot Spot")),
            HotspotTemp = Round1(Find(s, SensorKind.Temperature, "GPU Hot Spot")),
            ClockMhz = RoundLong(Find(s, SensorKind.Clock, "GPU Core")),
            PowerW = Round1(power),
            Voltage = voltage > 0 ? Math.Round(voltage.Value, 3) : null,
            FanRpm = RoundLong(fan),
            VramUsedMb = RoundLong(Find(s, SensorKind.SmallData, "GPU Memory Used")
                                   ?? Find(s, SensorKind.SmallData, "D3D Dedicated Memory Used")),
            VramTotalMb = RoundLong(Find(s, SensorKind.SmallData, "GPU Memory Total")
                                    ?? Find(s, SensorKind.SmallData, "D3D Dedicated Memory Total")),
        };
    }

    private static RamStats MapRam(IReadOnlyList<SensorReading> readings, MemoryInfo? fallback)
    {
        // LHM exposes "Total Memory" (physical) and "Virtual Memory" (commit) with identical sensor names.
        var mem = readings.Where(r => r.Hardware == HardwareKind.Memory
                                      && !r.HardwareName.Contains("Virtual", StringComparison.OrdinalIgnoreCase)).ToList();
        var usedGb = Find(mem, SensorKind.Data, "Memory Used");
        var availGb = Find(mem, SensorKind.Data, "Memory Available");

        if (usedGb.HasValue && availGb.HasValue)
        {
            return new RamStats
            {
                UsedMb = RoundLong(usedGb * 1024),
                TotalMb = RoundLong((usedGb + availGb) * 1024),
            };
        }

        return fallback is { } f
            ? new RamStats { UsedMb = f.UsedMb, TotalMb = f.TotalMb }
            : new RamStats { UsedMb = RoundLong(usedGb * 1024) };
    }

    private static List<FanStats> MapFans(IReadOnlyList<SensorReading> readings) =>
        readings
            .Where(r => r.Kind == SensorKind.Fan && r.Value is > 0)
            .Select(r => new FanStats(r.Name, (long)Math.Round(r.Value!.Value)))
            .ToList();

    private static double? Find(List<SensorReading> s, SensorKind kind, string name) =>
        s.FirstOrDefault(r => r.Kind == kind && r.Value.HasValue
                              && string.Equals(r.Name, name, StringComparison.OrdinalIgnoreCase))?.Value;

    private static double? Average(IEnumerable<SensorReading> s)
    {
        var values = s.Where(r => r.Value.HasValue).Select(r => (double)r.Value!.Value).ToList();
        return values.Count == 0 ? null : values.Average();
    }

    private static double? Round1(double? v) =>
        v.HasValue && double.IsFinite(v.Value) ? Math.Round(v.Value, 1) : null;

    private static long? RoundLong(double? v) =>
        v.HasValue && double.IsFinite(v.Value) ? (long)Math.Round(v.Value) : null;
}
