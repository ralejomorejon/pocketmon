using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace PcStats.Agent.Sensors;

/// <summary>Sums byte-counter deltas across operational, non-loopback interfaces.</summary>
public sealed class NetworkRateTracker
{
    private readonly Dictionary<string, (long Sent, long Received)> _last = new();
    private long _lastTicks;

    public readonly record struct Result(long? UpBps, long? DownBps, string? Adapter, long? SentBytes, long? ReceivedBytes);

    public Result Sample()
    {
        var now = Stopwatch.GetTimestamp();
        var current = new Dictionary<string, (long Sent, long Received)>();
        string? adapter = null;
        long bestReceived = -1;
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
                try
                {
                    var stats = nic.GetIPStatistics();
                    current[nic.Id] = (stats.BytesSent, stats.BytesReceived);

                    // Primary adapter: has an IPv4 default gateway; ties broken by traffic.
                    var hasGateway = nic.GetIPProperties().GatewayAddresses
                        .Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(System.Net.IPAddress.Any));
                    if (hasGateway && stats.BytesReceived > bestReceived)
                    {
                        bestReceived = stats.BytesReceived;
                        adapter = AdapterLabel(nic);
                    }
                }
                catch (NetworkInformationException)
                {
                    // Interface vanished between enumeration and query.
                }
            }
        }
        catch (NetworkInformationException)
        {
            return default;
        }

        long? up = null, down = null;
        if (_lastTicks != 0)
        {
            var seconds = Stopwatch.GetElapsedTime(_lastTicks, now).TotalSeconds;
            if (seconds > 0)
            {
                long sent = 0, received = 0;
                foreach (var (id, cur) in current)
                {
                    if (!_last.TryGetValue(id, out var prev)) continue;
                    sent += Math.Max(0, cur.Sent - prev.Sent);
                    received += Math.Max(0, cur.Received - prev.Received);
                }
                up = (long)Math.Round(sent / seconds);
                down = (long)Math.Round(received / seconds);
            }
        }

        _last.Clear();
        foreach (var kv in current) _last[kv.Key] = kv.Value;
        _lastTicks = now;
        return new Result(up, down, adapter, current.Values.Sum(v => v.Sent), current.Values.Sum(v => v.Received));
    }

    private static string AdapterLabel(NetworkInterface nic) => nic.NetworkInterfaceType switch
    {
        NetworkInterfaceType.Wireless80211 => "Wi-Fi",
        NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet or NetworkInterfaceType.FastEthernetT
            or NetworkInterfaceType.FastEthernetFx or NetworkInterfaceType.Ethernet3Megabit => "Ethernet",
        _ => nic.Name,
    };
}
