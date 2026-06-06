async def test_version_endpoint(client):
    """GET /version is unauthenticated and reports the running build."""
    resp = await client.get("/version")
    assert resp.status_code == 200
    data = resp.json()
    assert data["name"] == "Spotter API"
    assert data["version"] == "0.1.0"
    # Stamps default to "unknown" when not injected at deploy time.
    assert "commit" in data
    assert "built_at" in data
