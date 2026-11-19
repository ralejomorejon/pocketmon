namespace PcStats.Agent.Sensors;

public enum HardwareKind
{
    Cpu,
    GpuNvidia,
    GpuAmd,
    GpuIntel,
    Memory,
    Motherboard,
    Controller,
    Network,
    Storage,
    Other,
}

public enum SensorKind
{
    Load,
    Temperature,
    Clock,
    Power,
    SmallData,
    Data,
    Fan,
    Voltage,
    Other,
}

/// <summary>Hardware-independent view of one sensor, so mapping can be unit-tested.</summary>
/// <param name="HardwareId">Stable id of the owning top-level hardware (groups GPU sensors per device).</param>
public sealed record SensorReading(
    HardwareKind Hardware,
    string HardwareId,
    string HardwareName,
    SensorKind Kind,
    string Name,
    float? Value);

public readonly record struct MemoryInfo(long UsedMb, long TotalMb);
