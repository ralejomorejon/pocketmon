using System.Diagnostics;
using System.Drawing.Drawing2D;
using Microsoft.Extensions.Logging;
using PcStats.Agent.Config;
using PcStats.Agent.Infrastructure;
using PcStats.Agent.Net;

namespace PcStats.Agent.Tray;

public sealed class TrayContext : ApplicationContext
{
    private readonly AgentHost _host;
    private readonly ILogger _log;
    private readonly NotifyIcon _icon;
    private readonly System.Windows.Forms.Timer _refresh;
    private readonly ToolStripMenuItem _statusClients = new() { Enabled = false };
    private readonly ToolStripMenuItem _statusCode = new() { Enabled = false };
    private readonly ToolStripMenuItem _statusIps = new() { Enabled = false };
    private readonly ToolStripMenuItem _statusElevated = new() { Enabled = false };
    private readonly ToolStripMenuItem _installPawnIo;
    private readonly ToolStripMenuItem _startWithWindows;
    private bool _exiting;

    public TrayContext(AgentHost host, ILogger log)
    {
        _host = host;
        _log = log;

        _startWithWindows = new ToolStripMenuItem("Start with Windows", null, (_, _) => ToggleStartup());
        _installPawnIo = new ToolStripMenuItem("Install PawnIO driver (CPU temps)…", null, (_, _) => InstallPawnIo())
        {
            Visible = !PawnIo.IsInstalled,
        };

        var menu = new ContextMenuStrip();
        menu.Items.AddRange(
        [
            _statusClients, _statusCode, _statusIps, _statusElevated,
            new ToolStripSeparator(),
            new ToolStripMenuItem("Show pairing code", null, (_, _) => ShowPairingCode()),
            new ToolStripMenuItem("Open on phone (QR code)…", null, (_, _) => ShowWebAppQr()),
            _installPawnIo,
            new ToolStripMenuItem("Revoke all phones", null, (_, _) => RevokeAll()),
            _startWithWindows,
            new ToolStripMenuItem("Open config folder", null, (_, _) => OpenConfigFolder()),
            new ToolStripSeparator(),
            new ToolStripMenuItem("Exit", null, (_, _) => ExitThreadCore()),
        ]);
        menu.Opening += (_, _) =>
        {
            UpdateStatus();
            _startWithWindows.Checked = StartupTask.IsEnabled();
            _installPawnIo.Visible = !PawnIo.IsInstalled;
        };

        _icon = new NotifyIcon
        {
            Icon = CreateIcon(),
            ContextMenuStrip = menu,
            Visible = true,
            Text = "PocketMon Agent",
        };
        _icon.DoubleClick += (_, _) => ShowPairingCode();

        _refresh = new System.Windows.Forms.Timer { Interval = 1000 };
        _refresh.Tick += (_, _) => UpdateStatus();
        _refresh.Start();
        UpdateStatus();
    }

    private void UpdateStatus()
    {
        var ips = LanIps();
        var clients = _host.Server.ConnectedClients;
        _statusClients.Text = $"Connected phones: {clients}";
        _statusCode.Text = $"Pairing code: {_host.Pairing.CurrentCode}";
        _statusIps.Text = $"LAN IPs: {(ips.Length > 0 ? ips : "none")}";
        _statusElevated.Text = Elevation.IsElevated ? "Running as administrator" : "Not elevated (limited sensors, no FPS)";

        var tip = $"PocketMon Agent\nPhones: {clients}  Code: {_host.Pairing.CurrentCode}\n{ips}";
        _icon.Text = tip.Length > 127 ? tip[..127] : tip;
    }

    private static string LanIps() => string.Join(", ", LanAddresses.GetIpv4Interfaces().Select(i => i.Address));

    private void ShowPairingCode()
    {
        var ips = LanAddresses.GetIpv4Interfaces().Select(i => i.Address.ToString()).ToList();
        MessageBox.Show(
            $"Pairing code: {_host.Pairing.CurrentCode}\n\n" +
            "Enter this code in the phone app. It changes every 5 minutes and after each pairing.\n\n" +
            $"PC IPs: {(ips.Count > 0 ? string.Join(", ", ips) : "none")}\n" +
            "USB: connect the phone with USB debugging; the agent sets up adb reverse automatically.",
            "PocketMon Agent", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private WebAppQrForm? _qrForm;

    private void ShowWebAppQr()
    {
        if (_qrForm is { IsDisposed: false })
        {
            _qrForm.Activate();
            return;
        }
        _qrForm = new WebAppQrForm(_host.Pairing);
        _qrForm.Show();
    }

    private void InstallPawnIo()
    {
        if (MessageBox.Show(
                "CPU temperature, power and voltage need the PawnIO driver (a signed open-source kernel driver used by " +
                "LibreHardwareMonitor).\n\nInstall it now with winget? Restart the agent afterwards.",
                "PocketMon Agent", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
        if (!PawnIo.StartInstall())
            MessageBox.Show($"Could not start winget. Install manually: winget install {PawnIo.WingetId}",
                "PocketMon Agent", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }

    private void RevokeAll()
    {
        if (MessageBox.Show("Disconnect and forget all paired phones?", "PocketMon Agent",
                MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
        _host.Pairing.RevokeAll();
        _log.LogInformation("All phone tokens revoked from tray");
    }

    private void ToggleStartup()
    {
        var result = StartupTask.IsEnabled()
            ? StartupTask.Disable()
            : StartupTask.Enable(Environment.ProcessPath ?? Application.ExecutablePath);
        if (result.ExitCode != 0)
            MessageBox.Show($"schtasks failed:\n{result.Output}", "PocketMon Agent", MessageBoxButtons.OK, MessageBoxIcon.Error);
        _startWithWindows.Checked = StartupTask.IsEnabled();
    }

    private static void OpenConfigFolder()
    {
        Directory.CreateDirectory(SettingsStore.DefaultDirectory);
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{SettingsStore.DefaultDirectory}\"") { UseShellExecute = true });
    }

    protected override void ExitThreadCore()
    {
        if (_exiting) return;
        _exiting = true;
        _refresh.Stop();
        _icon.Visible = false;
        _icon.Dispose();
        base.ExitThreadCore();
    }

    private static Icon CreateIcon()
    {
        // The PocketMon .ico is embedded in the exe (ApplicationIcon); fall back to a drawn icon.
        try
        {
            if (Environment.ProcessPath is { } exe && Icon.ExtractAssociatedIcon(exe) is { } embedded) return embedded;
        }
        catch
        {
            // Fall through to the drawn icon.
        }
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using var bg = new SolidBrush(Color.FromArgb(0xE1, 0x06, 0x00));
            g.FillRectangle(bg, 1, 1, 30, 30);
            using var font = new Font("Arial Black", 11f, FontStyle.Bold, GraphicsUnit.Pixel);
            TextRenderer.DrawText(g, "PM", font, new Rectangle(0, 0, 32, 32), Color.White,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }
}
