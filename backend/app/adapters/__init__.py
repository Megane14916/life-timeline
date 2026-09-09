"""Adapters from versioned wire contracts to normalized domain records."""

from app.adapters.sync import SyncBatch, to_sync_batch

__all__ = ["SyncBatch", "to_sync_batch"]
