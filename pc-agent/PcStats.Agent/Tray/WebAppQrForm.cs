using PcStats.Agent.Net;
using PcStats.Agent.Pairing;
using PcStats.Agent.Protocol;
using QRCoder;

namespace PcStats.Agent.Tray;

/// <summary>Shows a QR code that opens the phone web app with the current pairing code filled in.</summary>
public sealed class WebAppQrForm : Form
{
    private readonly PairingService _pairing;
    private readonly ComboBox _address = new() { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Top };
    private readonly PictureBox _qr = new() { Dock = DockStyle.Fill, SizeMode = PictureBoxSizeMode.Zoom, BackColor = Color.White };
    private readonly Label _url = new() { Dock = DockStyle.Bottom, Height = 64, TextAlign = ContentAlignment.MiddleCenter };
    private readonly System.Windows.Forms.Timer _refresh = new() { Interval = 2000 };
    private string? _shown;

    public WebAppQrForm(PairingService pairing)
    {
        _pairing = pairing;
        Text = "PocketMon – open on phone";
        ClientSize = new Size(380, 500);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;

        var hint = new Label
        {
            Dock = DockStyle.Top,
            Height = 54,
            Text = "Scan with the phone camera (same Wi-Fi), then Add to Home Screen.\n" +
                   "iPhone over USB: enable Personal Hotspot and pick its 172.20.10.x address.",
            TextAlign = ContentAlignment.MiddleCenter,
        };
        Controls.Add(_qr);
        Controls.Add(_address);
        Controls.Add(hint);
        Controls.Add(_url);

        var ips = LanAddresses.GetIpv4Interfaces().Select(i => i.Address.ToString()).ToList();
        // Typical home LANs first; virtual adapters (WSL/Hyper-V 172.x) last, except the iPhone hotspot range.
        ips.Sort((a, b) => Rank(a).CompareTo(Rank(b)));
        _address.Items.AddRange([.. ips]);
        if (_address.Items.Count > 0) _address.SelectedIndex = 0;
        _address.SelectedIndexChanged += (_, _) => UpdateQr();
        _refresh.Tick += (_, _) => UpdateQr();
        _refresh.Start();
        UpdateQr();
    }

    private static int Rank(string ip) => ip switch
    {
        _ when ip.StartsWith("192.168.137.") => 3, // Windows mobile hotspot / ICS
        _ when ip.StartsWith("192.168.") || ip.StartsWith("10.") => 0,
        _ when ip.StartsWith("172.20.10.") => 1,   // iPhone Personal Hotspot over USB
        _ => 2,
    };

    internal static string BuildUrl(string ip, string code) => $"http://{ip}:{ProtocolConstants.WebSocketPort}/#code={code}";

    private void UpdateQr()
    {
        if (_address.SelectedItem is not string ip)
        {
            _url.Text = "No network address found";
            return;
        }
        var url = BuildUrl(ip, _pairing.CurrentCode);
        if (url == _shown) return;
        _shown = url;
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(url, QRCodeGenerator.ECCLevel.M);
        using var png = new PngByteQRCode(data);
        using var ms = new MemoryStream(png.GetGraphic(10));
        var old = _qr.Image;
        _qr.Image = Image.FromStream(ms);
        old?.Dispose();
        _url.Text = $"{url.Split('#')[0]}\nPairing code: {_pairing.CurrentCode}";
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _refresh.Dispose();
            _qr.Image?.Dispose();
        }
        base.Dispose(disposing);
    }
}
