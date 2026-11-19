using PcStats.Agent.Sensors;

namespace PcStats.Agent.Tests;

public class SnapshotMapperTests
{
    private static SensorReading Cpu(SensorKind kind, string name, float? value) =>
        new(HardwareKind.Cpu, "/cpu/0", "AMD Ryzen 7 7800X3D", kind, name, value);

    private static SensorReading Gpu(HardwareKind hw, string id, SensorKind kind, string name, float? value) =>
        new(hw, id, hw + " GPU", kind, name, value);

    [Fact]
    public void Empty_readings_yield_nulls_without_throwing()
    {
        var r = SnapshotMapper.Map([]);

        Assert.Null(r.Cpu.Usage);
        Assert.Null(r.Cpu.Temp);
        Assert.Null(r.Gpu.Usage);
        Assert.Null(r.Gpu.VramTotalMb);
        Assert.Null(r.Ram.UsedMb);
        Assert.Empty(r.Fans);
        Assert.Null(r.CpuName);
    }

    [Fact]
    public void Cpu_maps_total_load_package_temp_power_and_average_core_clock()
    {
        var r = SnapshotMapper.Map(
        [
            Cpu(SensorKind.Load, "CPU Total", 23.46f),
            Cpu(SensorKind.Load, "CPU Core #1", 90f),
            Cpu(SensorKind.Temperature, "Core (Tctl/Tdie)", 70f),
            Cpu(SensorKind.Temperature, "CPU Package", 61f),
            Cpu(SensorKind.Clock, "Core #1", 4800f),
            Cpu(SensorKind.Clock, "Core #2", 4900f),
            Cpu(SensorKind.Clock, "Bus Speed", 100f),
            Cpu(SensorKind.Power, "Package", 45.23f),
        ]);

        Assert.Equal(23.5, r.Cpu.Usage);
        Assert.Equal(61.0, r.Cpu.Temp);
        Assert.Equal(4850, r.Cpu.ClockMhz);
        Assert.Equal(45.2, r.Cpu.PowerW);
        Assert.Equal("AMD Ryzen 7 7800X3D", r.CpuName);
    }

    [Fact]
    public void Cpu_temp_falls_back_to_tctl_when_no_package()
    {
        var r = SnapshotMapper.Map([Cpu(SensorKind.Temperature, "Core (Tctl/Tdie)", 72f)]);
        Assert.Equal(72.0, r.Cpu.Temp);
    }

    [Fact]
    public void Cpu_temp_falls_back_to_core_max_then_core_average()
    {
        var max = SnapshotMapper.Map(
        [
            Cpu(SensorKind.Temperature, "Core #1", 50f),
            Cpu(SensorKind.Temperature, "Core Max", 66f),
        ]);
        Assert.Equal(66.0, max.Cpu.Temp);

        var avg = SnapshotMapper.Map(
        [
            Cpu(SensorKind.Temperature, "Core #1", 50f),
            Cpu(SensorKind.Temperature, "Core #2", 60f),
        ]);
        Assert.Equal(55.0, avg.Cpu.Temp);
    }

    [Fact]
    public void Cpu_package_temp_with_null_value_falls_through()
    {
        var r = SnapshotMapper.Map(
        [
            Cpu(SensorKind.Temperature, "CPU Package", null),
            Cpu(SensorKind.Temperature, "Core Max", 64f),
        ]);
        Assert.Equal(64.0, r.Cpu.Temp);
    }

    [Fact]
    public void Cpu_power_accepts_cpu_package_name()
    {
        var r = SnapshotMapper.Map([Cpu(SensorKind.Power, "CPU Package", 88f)]);
        Assert.Equal(88.0, r.Cpu.PowerW);
    }

    [Fact]
    public void Discrete_gpu_is_preferred_over_intel_integrated()
    {
        var r = SnapshotMapper.Map(
        [
            Gpu(HardwareKind.GpuIntel, "/gpu-intel/0", SensorKind.Load, "GPU Core", 5f),
            Gpu(HardwareKind.GpuNvidia, "/gpu-nvidia/0", SensorKind.Load, "GPU Core", 97f),
            Gpu(HardwareKind.GpuNvidia, "/gpu-nvidia/0", SensorKind.Temperature, "GPU Core", 70f),
            Gpu(HardwareKind.GpuNvidia, "/gpu-nvidia/0", SensorKind.Clock, "GPU Core", 2610f),
            Gpu(HardwareKind.GpuNvidia, "/gpu-nvidia/0", SensorKind.Power, "GPU Package", 280.44f),
            Gpu(HardwareKind.GpuNvidia, "/gpu-nvidia/0", SensorKind.SmallData, "GPU Memory Used", 9120f),
            Gpu(HardwareKind.GpuNvidia, "/gpu-nvidia/0", SensorKind.SmallData, "GPU Memory Total", 16376f),
        ]);

        Assert.Equal("GpuNvidia GPU", r.GpuName);
        Assert.Equal(97.0, r.Gpu.Usage);
        Assert.Equal(70.0, r.Gpu.Temp);
        Assert.Equal(2610, r.Gpu.ClockMhz);
        Assert.Equal(280.4, r.Gpu.PowerW);
        Assert.Equal(9120, r.Gpu.VramUsedMb);
        Assert.Equal(16376, r.Gpu.VramTotalMb);
    }

    [Fact]
    public void Same_vendor_gpu_with_more_vram_wins()
    {
        var r = SnapshotMapper.Map(
        [
            Gpu(HardwareKind.GpuAmd, "/gpu-amd/apu", SensorKind.SmallData, "GPU Memory Total", 512f),
            Gpu(HardwareKind.GpuAmd, "/gpu-amd/dgpu", SensorKind.SmallData, "GPU Memory Total", 16384f),
            Gpu(HardwareKind.GpuAmd, "/gpu-amd/dgpu", SensorKind.Load, "GPU Core", 40f),
        ]);
        Assert.Equal(16384, r.Gpu.VramTotalMb);
        Assert.Equal(40.0, r.Gpu.Usage);
    }

    [Fact]
    public void Gpu_temp_falls_back_to_hot_spot_and_power_to_gpu_power()
    {
        var r = SnapshotMapper.Map(
        [
            Gpu(HardwareKind.GpuAmd, "/gpu-amd/0", SensorKind.Temperature, "GPU Hot Spot", 81f),
            Gpu(HardwareKind.GpuAmd, "/gpu-amd/0", SensorKind.Power, "GPU Power", 150f),
        ]);
        Assert.Equal(81.0, r.Gpu.Temp);
        Assert.Equal(150.0, r.Gpu.PowerW);
    }

    [Fact]
    public void Intel_hybrid_core_names_are_used_for_clock_and_temp_average()
    {
        var r = SnapshotMapper.Map(
        [
            Cpu(SensorKind.Clock, "P-Core #1", 5000f),
            Cpu(SensorKind.Clock, "E-Core #1", 4000f),
            Cpu(SensorKind.Temperature, "P-Core #1", 70f),
            Cpu(SensorKind.Temperature, "E-Core #1", 60f),
            Cpu(SensorKind.Temperature, "P-Core #1 Distance to TjMax", 30f),
        ]);
        Assert.Equal(4500, r.Cpu.ClockMhz);
        Assert.Equal(65.0, r.Cpu.Temp);
    }

    [Fact]
    public void Zero_cpu_package_power_is_reported_as_null()
    {
        var r = SnapshotMapper.Map([Cpu(SensorKind.Power, "Package", 0f)]);
        Assert.Null(r.Cpu.PowerW);
    }

    [Fact]
    public void Virtual_memory_hardware_is_ignored_for_ram()
    {
        var r = SnapshotMapper.Map(
        [
            new(HardwareKind.Memory, "/vram", "Virtual Memory", SensorKind.Data, "Memory Used", 40f),
            new(HardwareKind.Memory, "/vram", "Virtual Memory", SensorKind.Data, "Memory Available", 8f),
            new(HardwareKind.Memory, "/ram", "Total Memory", SensorKind.Data, "Memory Used", 28f),
            new(HardwareKind.Memory, "/ram", "Total Memory", SensorKind.Data, "Memory Available", 4f),
        ]);
        Assert.Equal(28672, r.Ram.UsedMb);
        Assert.Equal(32768, r.Ram.TotalMb);
    }

    [Fact]
    public void Ram_converts_gb_to_mb_and_falls_back_to_global_memory_status()
    {
        var lhm = SnapshotMapper.Map(
        [
            new(HardwareKind.Memory, "/ram", "Generic Memory", SensorKind.Data, "Memory Used", 17.8f),
            new(HardwareKind.Memory, "/ram", "Generic Memory", SensorKind.Data, "Memory Available", 14.2f),
        ], new MemoryInfo(1, 2));
        Assert.Equal(18227, lhm.Ram.UsedMb);
        Assert.Equal(32768, lhm.Ram.TotalMb);

        var fallback = SnapshotMapper.Map([], new MemoryInfo(18234, 32768));
        Assert.Equal(18234, fallback.Ram.UsedMb);
        Assert.Equal(32768, fallback.Ram.TotalMb);
    }

    [Fact]
    public void Fans_include_only_positive_values_from_all_hardware()
    {
        var r = SnapshotMapper.Map(
        [
            new(HardwareKind.Motherboard, "/mb", "Board", SensorKind.Fan, "CPU Fan", 1200.4f),
            new(HardwareKind.Motherboard, "/mb", "Board", SensorKind.Fan, "Fan #2", 0f),
            new(HardwareKind.Motherboard, "/mb", "Board", SensorKind.Fan, "Fan #3", null),
            Gpu(HardwareKind.GpuNvidia, "/gpu-nvidia/0", SensorKind.Fan, "GPU Fan 1", 1650f),
        ]);
        Assert.Equal([new("CPU Fan", 1200), new("GPU Fan 1", 1650)], r.Fans);
    }

    [Fact]
    public void Core_loads_prefer_per_core_sensor_and_average_smt_threads()
    {
        var r = SnapshotMapper.Map(
        [
            Cpu(SensorKind.Load, "CPU Total", 50f),
            Cpu(SensorKind.Load, "CPU Core Max", 99f),
            Cpu(SensorKind.Load, "CPU Core #1 Thread #1", 20f),
            Cpu(SensorKind.Load, "CPU Core #1 Thread #2", 40f),
            Cpu(SensorKind.Load, "CPU Core #2", 75f),
            Cpu(SensorKind.Load, "CPU Core #2 Thread #1", 10f),
            Cpu(SensorKind.Load, "P-Core #3", 5f),
        ]);

        Assert.Equal([30.0, 75.0, 5.0], r.Cpu.CoreLoads);
    }

    [Fact]
    public void Cpu_tjmax_voltage_and_motherboard_cpu_fan()
    {
        var r = SnapshotMapper.Map(
        [
            Cpu(SensorKind.Temperature, "Core #1", 60f),
            Cpu(SensorKind.Temperature, "Core #1 Distance to TjMax", 40f),
            Cpu(SensorKind.Voltage, "CPU Core", 1.2361f),
            new(HardwareKind.Motherboard, "/mb", "Board", SensorKind.Fan, "System Fan", 800f),
            new(HardwareKind.Motherboard, "/mb", "Board", SensorKind.Fan, "CPU Fan", 1640f),
        ]);

        Assert.Equal(100.0, r.Cpu.TempLimit);
        Assert.Equal(1.236, r.Cpu.Voltage);
        Assert.Equal(1640, r.Cpu.FanRpm);
    }

    [Fact]
    public void Gpu_hotspot_fan_and_storage_temps_by_model()
    {
        var r = SnapshotMapper.Map(
        [
            Gpu(HardwareKind.GpuNvidia, "/gpu/0", SensorKind.Temperature, "GPU Core", 82f),
            Gpu(HardwareKind.GpuNvidia, "/gpu/0", SensorKind.Temperature, "GPU Hot Spot", 93f),
            Gpu(HardwareKind.GpuNvidia, "/gpu/0", SensorKind.Fan, "GPU Fan 1", 2210f),
            new(HardwareKind.Storage, "/nvme/0", "Samsung SSD 990 PRO 2TB", SensorKind.Temperature, "Temperature", 44f),
        ]);

        Assert.Equal(82.0, r.Gpu.Temp);
        Assert.Equal(93.0, r.Gpu.HotspotTemp);
        Assert.Equal(2210, r.Gpu.FanRpm);
        Assert.Equal(44.0, r.StorageTemps!["Samsung SSD 990 PRO 2TB"]);
        Assert.Equal(44.0, DiskReader.MatchTemp("Samsung SSD 990 PRO", r.StorageTemps));
    }
}