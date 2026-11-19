using System.Runtime.InteropServices;
using LibreHardwareMonitor.Hardware;
using Microsoft.Extensions.Logging;

namespace PcStats.Agent.Sensors;

/// <summary>Reads LibreHardwareMonitor sensors into flat <see cref="SensorReading"/>s. Never throws.</summary>
public sealed class HardwareSensorReader : IDisposable
{
    private readonly ILogger _log;
    private Computer? _computer;
    private bool _openFailed;

    public HardwareSensorReader(ILogger log) => _log = log;

    public IReadOnlyList<SensorReading> Read()
    {
        var result = new List<SensorReading>();
        var computer = EnsureOpen();
        if (computer is null) return result;

        foreach (var hw in SafeHardware(computer))
        {
            // Throughput comes from NetworkInterface counters; updating dozens of LHM network nodes is wasted work.
            if (hw.HardwareType == HardwareType.Network) continue;
            try
            {
                hw.Update();
                foreach (var sub in hw.SubHardware) sub.Update();
            }
            catch (Exception ex)
            {
                _log.LogDebug(ex, "Updating {Hardware} failed", hw.Name);
            }

            var kind = MapHardware(hw.HardwareType);
            var id = hw.Identifier.ToString();
            Collect(hw, kind, id, hw.Name, result);
            foreach (var sub in hw.SubHardware)
            {
                Collect(sub, kind, id, hw.Name, result);
            }
        }

        return result;
    }

    private Computer? EnsureOpen()
    {
        if (_computer is not null || _openFailed) return _computer;
        try
        {
            var c = new Computer
            {
                IsCpuEnabled = true,
                IsGpuEnabled = true,
                IsMotherboardEnabled = true,
                IsControllerEnabled = true,
                IsMemoryEnabled = true,
                IsNetworkEnabled = true,
                IsStorageEnabled = true,
            };
            c.Open();
            _computer = c;
            foreach (var hw in SafeHardware(c).Where(h => h.HardwareType != HardwareType.Network))
                _log.LogInformation("Hardware: {Type} {Name}", hw.HardwareType, hw.Name);
        }
        catch (Exception ex)
        {
            _openFailed = true;
            _log.LogError(ex, "LibreHardwareMonitor failed to open; sensor values will be null");
        }
        return _computer;
    }

    private IEnumerable<IHardware> SafeHardware(Computer c)
    {
        try
        {
            return c.Hardware.ToList();
        }
        catch (Exception ex)
        {
            _log.LogDebug(ex, "Enumerating hardware failed");
            return [];
        }
    }

    private static void Collect(IHardware hw, HardwareKind kind, string id, string name, List<SensorReading> into)
    {
        ISensor[] sensors;
        try
        {
            sensors = hw.Sensors;
        }
        catch
        {
            return;
        }

        foreach (var s in sensors)
        {
            float? value = s.Value;
            if (value is { } v && !float.IsFinite(v)) value = null;
            var sensorKind = MapSensor(s.SensorType);
            into.Add(new SensorReading(kind, id, name, sensorKind, s.Name, value));
        }
    }

    private static HardwareKind MapHardware(HardwareType t) => t switch
    {
        HardwareType.Cpu => HardwareKind.Cpu,
        HardwareType.GpuNvidia => HardwareKind.GpuNvidia,
        HardwareType.GpuAmd => HardwareKind.GpuAmd,
        HardwareType.GpuIntel => HardwareKind.GpuIntel,
        HardwareType.Memory => HardwareKind.Memory,
        HardwareType.Motherboard or HardwareType.SuperIO => HardwareKind.Motherboard,
        HardwareType.Cooler or HardwareType.EmbeddedController => HardwareKind.Controller,
        HardwareType.Network => HardwareKind.Network,
        HardwareType.Storage => HardwareKind.Storage,
        _ => HardwareKind.Other,
    };

    private static SensorKind MapSensor(SensorType t) => t switch
    {
        SensorType.Load => SensorKind.Load,
        SensorType.Temperature => SensorKind.Temperature,
        SensorType.Clock => SensorKind.Clock,
        SensorType.Power => SensorKind.Power,
        SensorType.SmallData => SensorKind.SmallData,
        SensorType.Data => SensorKind.Data,
        SensorType.Fan => SensorKind.Fan,
        SensorType.Voltage => SensorKind.Voltage,
        _ => SensorKind.Other,
    };

    public static MemoryInfo? GetGlobalMemoryStatus()
    {
        var status = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
        if (!GlobalMemoryStatusEx(ref status)) return null;
        const long mb = 1024 * 1024;
        var total = (long)(status.ullTotalPhys / mb);
        var avail = (long)(status.ullAvailPhys / mb);
        return new MemoryInfo(total - avail, total);
    }

    public void Dispose()
    {
        try
        {
            _computer?.Close();
        }
        catch
        {
            // Closing drivers on shutdown is best-effort.
        }
        _computer = null;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MEMORYSTATUSEX
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX lpBuffer);
}
