"""ASGI request-size guard for the multipart photo endpoint."""

from __future__ import annotations

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.schemas.photo_sync import PhotoSyncPolicy

TOO_LARGE_BODY = (
    b'{"error":{"code":"payload_too_large",'
    b'"message":"The photo sync request exceeds the size limit.","field":null}}'
)


class PhotoRequestSizeLimitMiddleware:
    """Reject oversized photo bodies before multipart parsing or disk spooling."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if (
            scope["type"] != "http"
            or scope.get("method") != "POST"
            or scope.get("path") != "/api/v1/sync/photos"
        ):
            await self.app(scope, receive, send)
            return

        content_length = next(
            (value for key, value in scope.get("headers", []) if key.lower() == b"content-length"),
            None,
        )
        if content_length is not None:
            try:
                if int(content_length) > PhotoSyncPolicy.MAX_REQUEST_BYTES:
                    await self._send_too_large(send)
                    return
            except ValueError:
                # Enforce the same cap incrementally if the header is malformed.
                pass

        received_bytes = 0
        exceeded = False
        body_complete = False
        pending_response: list[Message] = []

        async def limited_receive() -> Message:
            nonlocal received_bytes, exceeded, body_complete
            message = await receive()
            if message["type"] == "http.request":
                received_bytes += len(message.get("body", b""))
                body_complete = not message.get("more_body", False)
                if received_bytes > PhotoSyncPolicy.MAX_REQUEST_BYTES:
                    exceeded = True
                    body_complete = True
                    return {"type": "http.request", "body": b"", "more_body": False}
            return message

        async def filtered_send(message: Message) -> None:
            pending_response.append(message)

        application_error: Exception | None = None
        try:
            await self.app(scope, limited_receive, filtered_send)
        except Exception as error:
            application_error = error

        # A malformed multipart stream may make the parser stop before the body
        # is exhausted. Drain only through the same byte counter before replying.
        while not body_complete and not exceeded:
            message = await limited_receive()
            if message["type"] == "http.disconnect":
                body_complete = True

        if exceeded:
            await self._send_too_large(send)
            return
        if application_error is not None:
            raise application_error
        for message in pending_response:
            await send(message)

    @staticmethod
    async def _send_too_large(send: Send) -> None:
        await send(
            {
                "type": "http.response.start",
                "status": 413,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(TOO_LARGE_BODY)).encode("ascii")),
                ],
            }
        )
        await send({"type": "http.response.body", "body": TOO_LARGE_BODY, "more_body": False})
