import asyncio

from httpx import ASGITransport, AsyncClient, Response

from app.main import app


async def get(path: str) -> Response:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        return await client.get(path)


def test_health_returns_ok() -> None:
    response = asyncio.run(get("/api/v1/health"))

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_unknown_route_returns_not_found() -> None:
    response = asyncio.run(get("/api/v1/unknown"))

    assert response.status_code == 404
