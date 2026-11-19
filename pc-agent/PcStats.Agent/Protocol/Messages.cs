using System.Text.Json;
using System.Text.Json.Serialization;

namespace PcStats.Agent.Protocol;

public static class ProtocolConstants
{
    public const int Version = 1;
    public const int WebSocketPort = 28555;
    public const int DiscoveryPort = 28556;
    public const int DefaultIntervalMs = 1000;
    public const int MinIntervalMs = 200;
    public const int MaxIntervalMs = 5000;

    public static int ClampInterval(int ms) => Math.Clamp(ms, MinIntervalMs, MaxIntervalMs);
}

public static class ProtocolJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        PropertyNameCaseInsensitive = true,
        NumberHandling = JsonNumberHandling.AllowReadingFromString,
    };

    public static string Serialize<T>(T value) => JsonSerializer.Serialize(value, Options);
}

public sealed class Snapshot
{
    public string Type { get; init; } = "snapshot";
    public long Ts { get; init; }
    public CpuStats Cpu { get; init; } = new();
    public GpuStats Gpu { get; init; } = new();
    public RamStats Ram { get; init; } = new();
    public NetStats Net { get; init; } = new();
    public List<FanStats> Fans { get; init; } = [];
    public List<DiskStats> Disks { get; init; } = [];
    public FpsStats? Fps { get; init; }
}

public sealed class CpuStats
{
    public double? Usage { get; init; }
    public double? Temp { get; init; }
    /// <summary>Throttle temperature (TjMax) when the CPU reports it.</summary>
    public double? TempLimit { get; init; }
    public long? ClockMhz { get; init; }
    public double? PowerW { get; init; }
    public double? Voltage { get; init; }
    public long? FanRpm { get; init; }
    /// <summary>Per-core load in percent, in core order.</summary>
    public List<double> CoreLoads { get; init; } = [];
}

public sealed class GpuStats
{
    public double? Usage { get; init; }
    public double? Temp { get; init; }
    public double? HotspotTemp { get; init; }
    public long? ClockMhz { get; init; }
    public double? PowerW { get; init; }
    public double? Voltage { get; init; }
    public long? FanRpm { get; init; }
    public long? VramUsedMb { get; init; }
    public long? VramTotalMb { get; init; }
}

public sealed class DiskStats
{
    /// <summary>Drive letter, e.g. "C:".</summary>
    public string Name { get; init; } = "";
    public string? Model { get; init; }
    public long? FreeMb { get; init; }
    public long? TotalMb { get; init; }
    public double? Temp { get; init; }
}

public sealed class RamStats
{
    public long? UsedMb { get; init; }
    public long? TotalMb { get; init; }
}

public sealed class NetStats
{
    public long? UpBps { get; init; }
    public long? DownBps { get; init; }
    /// <summary>"Ethernet", "Wi-Fi" or similar for the primary (default-route) adapter.</summary>
    public string? Adapter { get; init; }
    /// <summary>Bytes sent/received since the interfaces came up (Windows counters).</summary>
    public long? SentBytes { get; init; }
    public long? ReceivedBytes { get; init; }
}

public sealed record FanStats(string Name, long Rpm);

public sealed record FpsStats(string App, double? Value);

public sealed class WelcomeMessage
{
    public string Type { get; init; } = "welcome";
    public int V { get; init; } = ProtocolConstants.Version;
    public string PcName { get; init; } = "";
    public string PcId { get; init; } = "";

    // Only sent in response to "pair".
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Token { get; init; }

    public string? CpuName { get; init; }
    public string? GpuName { get; init; }
    public int IntervalMs { get; init; }
}

public sealed record ErrorMessage(string Code, string Message)
{
    [JsonPropertyOrder(-1)]
    public string Type { get; init; } = "error";
}

public static class ErrorCodes
{
    public const string Unauthorized = "unauthorized";
    public const string BadCode = "bad_code";
    public const string BadRequest = "bad_request";
}

public sealed record DiscoveryBeaconMessage(string App, int V, string Name, int Port, string Id);

/// <summary>Any client → server message; unused fields stay null.</summary>
public sealed class ClientMessage
{
    public string? Type { get; set; }
    public int? V { get; set; }
    public string? Token { get; set; }
    public string? Code { get; set; }
    public string? ClientName { get; set; }
    public int? IntervalMs { get; set; }

    public static ClientMessage? TryParse(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<ClientMessage>(json, ProtocolJson.Options);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
