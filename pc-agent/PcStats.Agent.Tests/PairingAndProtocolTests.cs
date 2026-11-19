using System.Net;
using System.Text.Json;
using PcStats.Agent.Config;
using PcStats.Agent.Net;
using PcStats.Agent.Pairing;
using PcStats.Agent.Protocol;
using PcStats.Agent.Usb;

namespace PcStats.Agent.Tests;

public sealed class PairingServiceTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "PcStatsTests-" + Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        if (Directory.Exists(_dir)) Directory.Delete(_dir, true);
    }

    [Fact]
    public void Successful_pair_issues_token_stores_only_hash_and_rotates_code()
    {
        var store = new SettingsStore(_dir);
        using var pairing = new PairingService(store);
        var code = pairing.CurrentCode;
        Assert.Matches("^[0-9]{6}$", code);

        var result = pairing.TryPair(IPAddress.Loopback, code, out var token);

        Assert.Equal(PairResult.Success, result);
        Assert.NotNull(token);
        Assert.Equal(43, token.Length); // 32 bytes base64url without padding
        Assert.True(pairing.IsAuthorized(token));
        Assert.False(pairing.IsAuthorized(token + "x"));
        Assert.DoesNotContain(token, File.ReadAllText(Path.Combine(_dir, "config.json")));
        Assert.Contains(PairingService.HashToken(token), new SettingsStore(_dir).TokenHashes);
        if (pairing.CurrentCode != code)
            Assert.Equal(PairResult.BadCode, pairing.TryPair(IPAddress.Loopback, code, out _));
    }

    [Fact]
    public void Wrong_codes_are_rate_limited_per_ip()
    {
        using var pairing = new PairingService(new SettingsStore(_dir));
        var wrong = pairing.CurrentCode == "000000" ? "111111" : "000000";
        var ip = IPAddress.Parse("192.168.1.50");

        for (var i = 0; i < 5; i++) Assert.Equal(PairResult.BadCode, pairing.TryPair(ip, wrong, out _));

        Assert.Equal(PairResult.RateLimited, pairing.TryPair(ip, pairing.CurrentCode, out _));
        Assert.Equal(PairResult.Success, pairing.TryPair(IPAddress.Parse("192.168.1.51"), pairing.CurrentCode, out _));
    }

    [Fact]
    public void Rate_limiter_window_slides()
    {
        var now = DateTime.UtcNow;
        var limiter = new AttemptRateLimiter(5, TimeSpan.FromMinutes(1), () => now);
        for (var i = 0; i < 5; i++) Assert.True(limiter.TryAcquire("ip"));
        Assert.False(limiter.TryAcquire("ip"));

        now = now.AddSeconds(61);
        Assert.True(limiter.TryAcquire("ip"));
    }

    [Fact]
    public void Revoke_all_invalidates_tokens()
    {
        using var pairing = new PairingService(new SettingsStore(_dir));
        pairing.TryPair(IPAddress.Loopback, pairing.CurrentCode, out var token);

        pairing.RevokeAll();

        Assert.False(pairing.IsAuthorized(token));
    }
}

public class ProtocolAndHelpersTests
{
    [Fact]
    public void Snapshot_serializes_camel_case_and_keeps_nulls()
    {
        var json = ProtocolJson.Serialize(new Snapshot { Ts = 5, Cpu = new CpuStats { Usage = 1.5 } });
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;

        Assert.Equal("snapshot", root.GetProperty("type").GetString());
        Assert.Equal(1.5, root.GetProperty("cpu").GetProperty("usage").GetDouble());
        Assert.Equal(JsonValueKind.Null, root.GetProperty("cpu").GetProperty("temp").ValueKind);
        Assert.Equal(JsonValueKind.Null, root.GetProperty("gpu").GetProperty("vramUsedMb").ValueKind);
        Assert.Equal(JsonValueKind.Null, root.GetProperty("fps").ValueKind);
        Assert.Equal(JsonValueKind.Array, root.GetProperty("fans").ValueKind);
    }

    [Fact]
    public void Welcome_omits_token_unless_issued()
    {
        Assert.DoesNotContain("\"token\"", ProtocolJson.Serialize(new WelcomeMessage { PcId = "x" }));
        Assert.Contains("\"token\":\"abc\"", ProtocolJson.Serialize(new WelcomeMessage { PcId = "x", Token = "abc" }));
    }

    [Fact]
    public void Interval_is_clamped()
    {
        Assert.Equal(200, ProtocolConstants.ClampInterval(50));
        Assert.Equal(5000, ProtocolConstants.ClampInterval(60000));
        Assert.Equal(750, ProtocolConstants.ClampInterval(750));
    }

    [Fact]
    public void Broadcast_address_is_derived_from_mask()
    {
        Assert.Equal(IPAddress.Parse("192.168.1.255"),
            LanAddresses.ComputeBroadcast(IPAddress.Parse("192.168.1.23"), IPAddress.Parse("255.255.255.0")));
        Assert.Equal(IPAddress.Parse("10.0.255.255"),
            LanAddresses.ComputeBroadcast(IPAddress.Parse("10.0.3.4"), IPAddress.Parse("255.255.0.0")));
    }

    [Fact]
    public void Adb_devices_output_is_parsed()
    {
        const string output = "* daemon started successfully\r\nList of devices attached\r\nR5CT123\tdevice\r\nemulator-5554\toffline\r\nXYZ\tunauthorized\r\n\r\n";
        Assert.Equal(["R5CT123"], UsbHelper.ParseDevices(output));
    }

    [Fact]
    public void Firewall_rule_detection_matches_protocol_and_port()
    {
        const string output = """

            Rule Name:                            PocketMon Agent
            ----------------------------------------------------------------------
            Enabled:                              Yes
            Direction:                            In
            Protocol:                             TCP
            LocalPort:                            28555

            Rule Name:                            PocketMon Agent
            ----------------------------------------------------------------------
            Enabled:                              Yes
            Direction:                            In
            Protocol:                             UDP
            LocalPort:                            28556
            Ok.
            """;
        Assert.True(FirewallManager.HasRule(output, "TCP", 28555));
        Assert.True(FirewallManager.HasRule(output, "UDP", 28556));
        Assert.False(FirewallManager.HasRule(output, "TCP", 28556));
        Assert.False(FirewallManager.HasRule("No rules match the specified criteria.", "TCP", 28555));
    }
}
