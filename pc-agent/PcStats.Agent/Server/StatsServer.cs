using System.Collections.Concurrent;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using PcStats.Agent.Config;
using PcStats.Agent.Pairing;
using PcStats.Agent.Protocol;
using PcStats.Agent.Sensors;

namespace PcStats.Agent.Server;

/// <summary>Kestrel host serving GET /health and the /ws WebSocket endpoint.</summary>
public sealed class StatsServer : IAsyncDisposable
{
    private static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(5);

    private readonly ILoggerFactory _loggerFactory;
    private readonly ILogger _log;
    private readonly SettingsStore _settings;
    private readonly PairingService _pairing;
    private readonly Sampler _sampler;
    private readonly Games.GameCatalog? _games;
    private readonly Games.GameLogoService? _logos;
    private readonly ConcurrentDictionary<Guid, CancellationTokenSource> _clients = new();
    private WebApplication? _app;

    public StatsServer(ILoggerFactory loggerFactory, SettingsStore settings, PairingService pairing, Sampler sampler,
        Games.GameCatalog? games = null, Games.GameLogoService? logos = null)
    {
        _loggerFactory = loggerFactory;
        _log = loggerFactory.CreateLogger("Server");
        _settings = settings;
        _pairing = pairing;
        _sampler = sampler;
        _games = games;
        _logos = logos;
        _pairing.TokensRevoked += DisconnectAll;
    }

    public int ConnectedClients => _clients.Count;

    public event Action? ClientsChanged;

    public async Task StartAsync(int port = ProtocolConstants.WebSocketPort)
    {
        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions { ContentRootPath = AppContext.BaseDirectory });
        builder.Logging.ClearProviders();
        builder.WebHost.UseKestrel(o => o.Listen(IPAddress.Any, port));

        var app = builder.Build();
        app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(15) });
        UseWebApp(app);
        app.MapGet("/health", () => Results.Text("ok"));
        app.MapGet("/logos/{file}", (string file, HttpContext ctx) =>
        {
            if (_games is null || _logos is null || !file.EndsWith(".png", StringComparison.Ordinal)) return Results.NotFound();
            var path = _logos.GetFile(file[..^4], _games);
            if (path is null) return Results.NotFound();
            ctx.Response.Headers.CacheControl = "public, max-age=86400";
            return Results.File(path, "image/png");
        });
        app.Map("/ws", HandleWebSocketAsync);

        await app.StartAsync();
        _app = app;
        _log.LogInformation("Listening on 0.0.0.0:{Port} (web app /, WebSocket /ws, GET /health)", port);
    }

    /// <summary>Serves the phone web app (PWA) from wwwroot next to the executable, if present.</summary>
    private void UseWebApp(WebApplication app)
    {
        var root = Path.Combine(AppContext.BaseDirectory, "wwwroot");
        if (!Directory.Exists(root))
        {
            _log.LogWarning("Web app folder {Root} not found; only the native app can connect", root);
            return;
        }
        var files = new Microsoft.Extensions.FileProviders.PhysicalFileProvider(root);
        app.UseDefaultFiles(new DefaultFilesOptions { FileProvider = files });
        app.UseStaticFiles(new StaticFileOptions
        {
            FileProvider = files,
            // The app is updated with the agent; always revalidate so phones pick up new versions.
            OnPrepareResponse = ctx => ctx.Context.Response.Headers.CacheControl = "no-cache",
        });
    }

    private async Task HandleWebSocketAsync(HttpContext context)
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteAsync("WebSocket required");
            return;
        }

        var remote = context.Connection.RemoteIpAddress;
        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted);
        var session = new ClientSession(socket, _log);
        var id = Guid.NewGuid();

        try
        {
            var interval = await HandshakeAsync(session, remote, cts.Token);
            if (interval is null) return;

            _clients[id] = cts;
            ClientsChanged?.Invoke();
            await RunAuthenticatedAsync(session, interval.Value, cts.Token);
        }
        catch (OperationCanceledException)
        {
            // Client went away or was revoked.
        }
        catch (WebSocketException ex)
        {
            _log.LogDebug("WebSocket from {Remote} closed: {Message}", remote, ex.Message);
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "WebSocket session from {Remote} failed", remote);
        }
        finally
        {
            if (_clients.TryRemove(id, out _)) ClientsChanged?.Invoke();
            await session.CloseQuietlyAsync();
        }
    }

    /// <summary>Returns the client's interval on success, or null after sending an error.</summary>
    private async Task<int?> HandshakeAsync(ClientSession session, IPAddress? remote, CancellationToken ct)
    {
        // Cancelling a pending WebSocket receive aborts the socket, so race it against a delay instead
        // to still be able to send the error frame.
        var receive = session.ReceiveTextAsync(ct);
        if (await Task.WhenAny(receive, Task.Delay(HandshakeTimeout, ct)) != receive)
        {
            _ = receive.ContinueWith(t => _ = t.Exception, TaskContinuationOptions.OnlyOnFaulted);
            _log.LogInformation("Client {Remote} sent no handshake within 5 s", remote);
            await session.SendAsync(new ErrorMessage(ErrorCodes.BadRequest, "Handshake timeout"), ct);
            return null;
        }

        var text = await receive;
        if (text is null) return null;
        var msg = ClientMessage.TryParse(text);
        if (msg is null || (msg.V is { } v && v != ProtocolConstants.Version))
        {
            await session.SendAsync(new ErrorMessage(ErrorCodes.BadRequest, "Invalid handshake message"), ct);
            return null;
        }

        string? issuedToken = null;
        switch (msg.Type)
        {
            case "hello":
                if (!_pairing.IsAuthorized(msg.Token))
                {
                    _log.LogInformation("Rejected hello from {Remote} ({Client}): unknown token", remote, msg.ClientName);
                    await session.SendAsync(new ErrorMessage(ErrorCodes.Unauthorized, "Unknown or revoked token; pair again"), ct);
                    return null;
                }
                break;

            case "pair":
                var result = _pairing.TryPair(remote, msg.Code, out issuedToken);
                if (result != PairResult.Success)
                {
                    var message = result == PairResult.RateLimited
                        ? "Too many pairing attempts; wait a minute"
                        : "Wrong pairing code";
                    _log.LogInformation("Pairing from {Remote} ({Client}) failed: {Result}", remote, msg.ClientName, result);
                    await session.SendAsync(new ErrorMessage(ErrorCodes.BadCode, message), ct);
                    return null;
                }
                _log.LogInformation("Paired new client {Client} from {Remote}", msg.ClientName, remote);
                break;

            default:
                await session.SendAsync(new ErrorMessage(ErrorCodes.BadRequest, "Expected hello or pair"), ct);
                return null;
        }

        var interval = _settings.IntervalMs;
        _sampler.WaitForFirstSample(TimeSpan.FromSeconds(3));
        await session.SendAsync(new WelcomeMessage
        {
            PcName = Environment.MachineName,
            PcId = _settings.PcId,
            Token = issuedToken,
            CpuName = _sampler.CpuName,
            GpuName = _sampler.GpuName,
            IntervalMs = interval,
        }, ct);
        _log.LogInformation("Client {Client} connected from {Remote}", msg.ClientName, remote);
        return interval;
    }

    private async Task RunAuthenticatedAsync(ClientSession session, int initialInterval, CancellationToken ct)
    {
        var interval = initialInterval;
        using var loopCts = CancellationTokenSource.CreateLinkedTokenSource(ct);

        var sendLoop = Task.Run(async () =>
        {
            var sw = new System.Diagnostics.Stopwatch();
            while (!loopCts.IsCancellationRequested)
            {
                sw.Restart();
                await session.SendAsync(_sampler.Latest, loopCts.Token);
                // Poll in small steps so a setInterval takes effect without waiting out the old interval.
                while (sw.ElapsedMilliseconds < Volatile.Read(ref interval))
                    await Task.Delay(Math.Clamp(Volatile.Read(ref interval) - (int)sw.ElapsedMilliseconds, 1, 50), loopCts.Token);
            }
        }, loopCts.Token);

        try
        {
            while (!ct.IsCancellationRequested)
            {
                var text = await session.ReceiveTextAsync(ct);
                if (text is null) break;
                var msg = ClientMessage.TryParse(text);
                if (msg?.Type == "setInterval" && msg.IntervalMs is { } ms)
                {
                    Volatile.Write(ref interval, ProtocolConstants.ClampInterval(ms));
                    _log.LogDebug("Client interval set to {Interval} ms", interval);
                }
            }
        }
        finally
        {
            loopCts.Cancel();
            try
            {
                await sendLoop;
            }
            catch (Exception)
            {
                // Send loop ends with cancellation or a closed socket.
            }
        }
    }

    private void DisconnectAll()
    {
        foreach (var cts in _clients.Values)
        {
            try
            {
                cts.Cancel();
            }
            catch (ObjectDisposedException)
            {
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        _pairing.TokensRevoked -= DisconnectAll;
        DisconnectAll();
        if (_app is not null)
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
            try
            {
                await _app.StopAsync(cts.Token);
            }
            catch (Exception)
            {
            }
            await _app.DisposeAsync();
        }
    }
}

internal sealed class ClientSession
{
    private const int MaxMessageBytes = 16 * 1024;

    private readonly WebSocket _socket;
    private readonly ILogger _log;
    private readonly SemaphoreSlim _sendLock = new(1, 1);

    public ClientSession(WebSocket socket, ILogger log)
    {
        _socket = socket;
        _log = log;
    }

    public async Task SendAsync<T>(T message, CancellationToken ct)
    {
        var bytes = Encoding.UTF8.GetBytes(ProtocolJson.Serialize(message));
        await _sendLock.WaitAsync(ct);
        try
        {
            await _socket.SendAsync(bytes, WebSocketMessageType.Text, true, ct);
        }
        finally
        {
            _sendLock.Release();
        }
    }

    /// <summary>Returns the next text message, or null when the client closed the socket.</summary>
    public async Task<string?> ReceiveTextAsync(CancellationToken ct)
    {
        var buffer = new byte[4096];
        using var ms = new MemoryStream();
        while (true)
        {
            var result = await _socket.ReceiveAsync(buffer, ct);
            if (result.MessageType == WebSocketMessageType.Close) return null;
            ms.Write(buffer, 0, result.Count);
            if (ms.Length > MaxMessageBytes) throw new WebSocketException("Message too large");
            if (result.EndOfMessage)
            {
                if (result.MessageType != WebSocketMessageType.Text) return "";
                return Encoding.UTF8.GetString(ms.GetBuffer(), 0, (int)ms.Length);
            }
        }
    }

    public async Task CloseQuietlyAsync()
    {
        try
        {
            if (_socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                // Output-only close: a receive may still be pending (handshake timeout).
                await _socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "bye", cts.Token);
            }
        }
        catch (Exception ex)
        {
            _log.LogTrace(ex, "Close failed");
        }
    }
}
