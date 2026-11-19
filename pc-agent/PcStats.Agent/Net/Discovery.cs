using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using Microsoft.Extensions.Logging;
using PcStats.Agent.Protocol;

namespace PcStats.Agent.Net;

public static class LanAddresses
{
    public sealed record Ipv4Interface(IPAddress Address, IPAddress Broadcast);

    public static List<Ipv4Interface> GetIpv4Interfaces()
    {
        var result = new List<Ipv4Interface>();
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
                foreach (var ua in nic.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(ua.Address)) continue;
                    var bytes = ua.Address.GetAddressBytes();
                    if (bytes[0] == 169 && bytes[1] == 254) continue;
                    result.Add(new Ipv4Interface(ua.Address, ComputeBroadcast(ua.Address, ua.IPv4Mask)));
                }
            }
        }
        catch (NetworkInformationException)
        {
        }
        return result;
    }

    public static IPAddress ComputeBroadcast(IPAddress address, IPAddress? mask)
    {
        if (mask is null || mask.Equals(IPAddress.Any)) return IPAddress.Broadcast;
        var a = address.GetAddressBytes();
        var m = mask.GetAddressBytes();
        var b = new byte[4];
        for (var i = 0; i < 4; i++) b[i] = (byte)(a[i] | ~m[i]);
        return new IPAddress(b);
    }
}

/// <summary>Broadcasts the discovery beacon every 2 s on every IPv4 interface.</summary>
public sealed class DiscoveryBeacon : IDisposable
{
    private static readonly TimeSpan Period = TimeSpan.FromSeconds(2);

    private readonly ILogger _log;
    private readonly byte[] _payload;
    private readonly CancellationTokenSource _cts = new();
    private Task? _loop;

    public DiscoveryBeacon(ILogger log, string pcId)
    {
        _log = log;
        var json = ProtocolJson.Serialize(new DiscoveryBeaconMessage(
            "pcstats", ProtocolConstants.Version, Environment.MachineName, ProtocolConstants.WebSocketPort, pcId));
        _payload = Encoding.UTF8.GetBytes(json);
    }

    public void Start() => _loop = Task.Run(() => RunAsync(_cts.Token));

    private async Task RunAsync(CancellationToken ct)
    {
        var warned = new HashSet<string>();
        using var timer = new PeriodicTimer(Period);
        do
        {
            foreach (var iface in LanAddresses.GetIpv4Interfaces())
            {
                try
                {
                    using var udp = new UdpClient(new IPEndPoint(iface.Address, 0)) { EnableBroadcast = true };
                    var targets = new HashSet<IPAddress> { iface.Broadcast, IPAddress.Broadcast };
                    foreach (var target in targets)
                        await udp.SendAsync(_payload, new IPEndPoint(target, ProtocolConstants.DiscoveryPort), ct);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch (Exception ex)
                {
                    if (warned.Add(iface.Address.ToString()))
                        _log.LogDebug("Discovery send on {Address} failed: {Message}", iface.Address, ex.Message);
                }
            }
        } while (await WaitAsync(timer, ct));
    }

    private static async Task<bool> WaitAsync(PeriodicTimer timer, CancellationToken ct)
    {
        try
        {
            return await timer.WaitForNextTickAsync(ct);
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        try
        {
            _loop?.Wait(TimeSpan.FromSeconds(2));
        }
        catch
        {
        }
    }
}
