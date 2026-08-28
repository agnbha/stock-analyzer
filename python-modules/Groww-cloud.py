# ============================================================
# DIP_BUY_STRATEGY.PY
# Buy on >2% price dip within the last 15 minutes
# ============================================================

# ============================================================
# SECTION 1: CONFIGURATION
# ============================================================

AUTH_CONFIG = {
    "api_key": "eyJraWQiOiJaTUtjVXciLCJhbGciOiJFUzI1NiJ9.eyJleHAiOjI1NzYyMDkwNjMsImlhdCI6MTc4NzgwOTA2MywibmJmIjoxNzg3ODA5MDYzLCJzdWIiOiJ7XCJ0b2tlblJlZklkXCI6XCJlYjY5NDViOS1mYmEyLTRlN2QtYTQyOS05MDg5ZWUwZmYyZmFcIixcInZlbmRvckludGVncmF0aW9uS2V5XCI6XCJlMzFmZjIzYjA4NmI0MDZjODg3NGIyZjZkODQ5NTMxM1wiLFwidXNlckFjY291bnRJZFwiOlwiZWMzYTZiNmEtZGExMi00ZjVjLTk5ZmMtYmUyMzA3ODZlMzA4XCIsXCJkZXZpY2VJZFwiOlwiMmE0MzRmYWUtMjUxOC01OWIzLThkMmQtNWIxNTkyMDA4MDkwXCIsXCJzZXNzaW9uSWRcIjpcImI1Y2VhNWNlLWM2ODUtNDlhNS05ZWUwLWVkOGViN2E4MWQ0MVwiLFwiYWRkaXRpb25hbERhdGFcIjpcIno1NC9NZzltdjE2WXdmb0gvS0EwYkd4M0N3ZHhDUDNGT0hLcUJ5SzMzRDVSTkczdTlLa2pWZDNoWjU1ZStNZERhWXBOVi9UOUxIRmtQejFFQisybTdRPT1cIixcInJvbGVcIjpcImF1dGgtdG90cFwiLFwic291cmNlSXBBZGRyZXNzXCI6XCIyNDAxOjQ5MDA6MWNiZDo0MzgyOjE0Y2Q6NjNlZTo1OTM4OjE0NTUsMTA0LjIyLjQ3LjE0NiwzNS4yNDEuMjMuMTIzXCIsXCJ0d29GYUV4cGlyeVRzXCI6MjU3NjIwOTA2Mzk1MSxcInZlbmRvck5hbWVcIjpcImdyb3d3QXBpXCJ9IiwiaXNzIjoiYXBleC1hdXRoLXByb2QtYXBwIn0.0WLEoUV61Xt8a4lHhu2q23XSIAIrxU8pFWHc3pK4bwl2tHh6AcnZTWTNXGQuWMKxtteE6hhcSTxz56nnypeCbQ",
    "api_secret": "wFRsyHui_ZDiiU^cQCTC$_!Fn1S)xS5t",
}

STRATEGY_CONFIG = {
    # Trading Universe
    "symbols": ["RELIANCE", "TCS", "INFY"],
    "exchange": "NSE",
    "segment": "CASH",
    "product": "CNC",

    # Dip Detection
    "dip_threshold_percent": 2.0,       # Minimum % dip to trigger buy
    "lookback_minutes": 15,             # Lookback window in minutes

    # Position Sizing
    "initial_capital": 100000.0,        # Total capital in INR
    "position_size_percent": 20.0,      # % of capital per trade

    # Exit Parameters
    "stop_loss_percent": 2.0,           # SL below entry price
    "take_profit_percent": 4.0,         # TP above entry price

    # Timing
    "trading_start_time": "09:20:00",   # Avoid opening volatility
    "trading_end_time": "15:10:00",     # Square off before close
    "check_interval_seconds": 60,       # Polling interval
}

# ============================================================
# SECTION 2: IMPORTS
# ============================================================

import time
from datetime import datetime, timedelta

import pandas as pd
from growwapi import GrowwAPI

# ============================================================
# SECTION 3: HELPER FUNCTIONS
# ============================================================

def is_market_open(current_time: datetime) -> bool:
    """Check if NSE market is open (9:15 AM - 3:30 PM, weekdays)."""
    if current_time.weekday() >= 5:
        return False
    from datetime import time as dt_time
    return dt_time(9, 15) <= current_time.time() <= dt_time(15, 30)


def is_within_trading_window(current_time: datetime, start: str, end: str) -> bool:
    """Check if current time is within the configured trading window."""
    from datetime import time as dt_time
    fmt = "%H:%M:%S"
    start_t = datetime.strptime(start, fmt).time()
    end_t   = datetime.strptime(end, fmt).time()
    return start_t <= current_time.time() <= end_t


def calculate_dip_percent(high_price: float, current_price: float) -> float:
    """
    Calculate percentage dip from the high price.

    Args:
        high_price: Highest price in the lookback window
        current_price: Latest traded price

    Returns:
        Dip percentage (positive = price fell)
    """
    if high_price <= 0:
        return 0.0
    return ((high_price - current_price) / high_price) * 100.0


def calculate_position_size(capital: float, position_size_percent: float, price: float) -> int:
    """
    Calculate number of shares to buy based on capital allocation.

    Args:
        capital: Available capital in INR
        position_size_percent: Percentage of capital to allocate
        price: Current price per share

    Returns:
        Number of shares (integer, minimum 1)
    """
    allocated = capital * (position_size_percent / 100.0)
    qty = int(allocated // price)
    return max(qty, 1)


def calculate_sl_tp(entry_price: float, sl_pct: float, tp_pct: float) -> tuple:
    """
    Calculate stop-loss and take-profit prices.

    Args:
        entry_price: Order entry price
        sl_pct: Stop-loss percentage below entry
        tp_pct: Take-profit percentage above entry

    Returns:
        Tuple of (stop_loss_price, take_profit_price)
    """
    sl = round(entry_price * (1 - sl_pct / 100.0), 2)
    tp = round(entry_price * (1 + tp_pct / 100.0), 2)
    return sl, tp


def parse_candles_to_df(response: dict) -> pd.DataFrame:
    """Parse Groww historical candles API response into a DataFrame."""
    base_cols = ["timestamp", "open", "high", "low", "close", "volume", "oi"]
    candles = response.get("candles", [])
    if not candles:
        return pd.DataFrame(columns=base_cols)
    # Groww returns 6 fields for CASH candles (no open interest) and 7 for F&O.
    n_cols = len(candles[0])
    df = pd.DataFrame(candles, columns=base_cols[:n_cols])
    df["timestamp"] = pd.to_datetime(df["timestamp"])
    return df


def generate_order_reference_id(symbol: str) -> str:
    """Generate a unique order reference ID (8-20 alphanumeric, max 2 hyphens)."""
    ts = datetime.now().strftime("%H%M%S")
    tag = symbol[:4].upper()
    return f"DIP-{tag}-{ts}"          # e.g. "DIP-RELI-093015"

# ============================================================
# SECTION 4: BASE STRATEGY
# ============================================================

class BaseStrategy:
    """
    Core strategy logic — mode-agnostic.
    Never instantiated directly.
    """

    def __init__(self, groww: GrowwAPI, config: dict):
        self.groww  = groww
        self.config = config

        # State
        self.positions: dict  = {}          # symbol -> {entry, qty, sl, tp}
        self.capital: float   = config["initial_capital"]
        self.total_pnl: float = 0.0

        # Cache instruments at startup
        self.instruments: dict = {}
        print("Fetching instrument data for configured symbols...")
        for symbol in self.config["symbols"]:
            try:
                inst = self.groww.get_instrument_by_exchange_and_trading_symbol(
                    exchange=self.config["exchange"],
                    trading_symbol=symbol,
                )
                self.instruments[symbol] = inst
                print(f"  [{symbol}] Instrument loaded: {inst['groww_symbol']}")
            except Exception as e:
                print(f"  [{symbol}] ERROR loading instrument: {e}")

    # ----------------------------------------------------------
    # Indicator / Signal Logic
    # ----------------------------------------------------------

    def calculate_indicators(self, df: pd.DataFrame) -> dict:
        """
        Derive dip-detection indicators from OHLCV data.

        Returns dict with:
            high_price    - highest price in lookback window
            current_price - last close price
            dip_pct       - percentage dip from high
        """
        if df.empty or len(df) < 2:
            return {}

        high_price    = float(df["high"].max())
        current_price = float(df["close"].iloc[-1])
        dip_pct       = calculate_dip_percent(high_price, current_price)

        return {
            "high_price":    high_price,
            "current_price": current_price,
            "dip_pct":       dip_pct,
        }

    def generate_signal(self, symbol: str, df: pd.DataFrame) -> str | None:
        """
        Generate BUY signal when price dips > threshold% from 15-min high.

        Returns:
            "BUY" if dip condition met and no open position, else None
        """
        indicators = self.calculate_indicators(df)
        if not indicators:
            return None

        dip_pct       = indicators["dip_pct"]
        current_price = indicators["current_price"]
        high_price    = indicators["high_price"]
        threshold     = self.config["dip_threshold_percent"]
        if dip_pct >= threshold:
            print(f"[{symbol}] Dip detected: {dip_pct:.2f}% from high ₹{high_price:.2f} → current ₹{current_price:.2f}")
            return "BUY"

        print(f"[{symbol}] No signal | Dip={dip_pct:.2f}% (threshold={threshold}%) | Price=₹{current_price:.2f}")
        return None

    def monitor_positions(self, current_prices: dict) -> None:
        """
        Check open positions against SL/TP levels and trigger exits.

        Args:
            current_prices: dict of symbol -> latest price
        """
        for symbol in list(self.positions.keys()):
            pos   = self.positions[symbol]
            price = current_prices.get(symbol)
            if price is None:
                continue

            sl = pos["sl"]
            tp = pos["tp"]

            if price <= sl:
                pnl = (price - pos["entry"]) * pos["qty"]
                print(f"[{symbol}] Stop loss triggered at ₹{price:.2f} | P&L: ₹{pnl:.2f}")
                self._close_position(symbol, price, "STOP_LOSS")

            elif price >= tp:
                pnl = (price - pos["entry"]) * pos["qty"]
                print(f"[{symbol}] Take profit hit at ₹{price:.2f} | P&L: ₹{pnl:.2f}")
                self._close_position(symbol, price, "TAKE_PROFIT")

    def _open_position(self, symbol: str, entry_price: float, qty: int) -> None:
        """Record a newly opened position in state."""
        sl, tp = calculate_sl_tp(
            entry_price,
            self.config["stop_loss_percent"],
            self.config["take_profit_percent"],
        )
        self.positions[symbol] = {
            "entry":      entry_price,
            "qty":        qty,
            "sl":         sl,
            "tp":         tp,
            "entry_time": datetime.now(),
        }
        self.capital -= entry_price * qty
        print(f"[{symbol}] Position opened | Entry=₹{entry_price:.2f} | Qty={qty} | SL=₹{sl:.2f} | TP=₹{tp:.2f}")

    def _close_position(self, symbol: str, exit_price: float, reason: str) -> None:
        """Record a closed position and update P&L."""
        pos      = self.positions.pop(symbol)
        pnl      = (exit_price - pos["entry"]) * pos["qty"]
        duration = datetime.now() - pos["entry_time"]
        self.total_pnl += pnl
        self.capital   += exit_price * pos["qty"]
        print(f"[{symbol}] Position closed | Reason={reason} | Exit=₹{exit_price:.2f} | "
            f"P&L=₹{pnl:.2f} | Hold={str(duration).split('.')[0]} | Total P&L=₹{self.total_pnl:.2f}")


# ============================================================
# SECTION 5: LIVE STRATEGY
# ============================================================

class LiveStrategy(BaseStrategy):
    """Live trading implementation using the Groww API."""

    def __init__(self, groww: GrowwAPI, config: dict):
        super().__init__(groww, config)
        print("Live strategy initialised.")

    # ----------------------------------------------------------
    # Data Fetching
    # ----------------------------------------------------------

    def fetch_data(self, symbol: str) -> pd.DataFrame:
        """
        Fetch the last ~20 minutes of 1-min candles to cover the 15-min lookback.

        Args:
            symbol: Trading symbol string

        Returns:
            DataFrame of recent candles (last 15 minutes)
        """
        instrument = self.instruments.get(symbol)
        if instrument is None:
            print(f"[{symbol}] ERROR: Instrument not found in cache.")
            return pd.DataFrame()

        end_dt   = datetime.now()
        start_dt = end_dt - timedelta(minutes=20)   # slight buffer

        start_str = start_dt.strftime("%Y-%m-%d %H:%M:%S")
        end_str   = end_dt.strftime("%Y-%m-%d %H:%M:%S")

        try:
            response = self.groww.get_historical_candles(
                exchange=instrument["exchange"],
                segment=instrument["segment"],
                groww_symbol=instrument["groww_symbol"],
                start_time=start_str,
                end_time=end_str,
                candle_interval=self.groww.CANDLE_INTERVAL_MIN_1,
            )
            df = parse_candles_to_df(response)

            # Keep only the last 15 minutes
            if not df.empty:
                cutoff = pd.Timestamp(end_dt - timedelta(minutes=self.config["lookback_minutes"]))
                df = df[df["timestamp"] >= cutoff].reset_index(drop=True)

            return df

        except Exception as e:
            print(f"[{symbol}] ERROR fetching data: {e}")
            return pd.DataFrame()

    def get_current_price(self, symbol: str) -> float | None:
        """Fetch latest traded price via LTP API."""
        instrument = self.instruments.get(symbol)
        if instrument is None:
            return None
        try:
            key  = f"{instrument['exchange']}_{instrument['trading_symbol']}"
            ltps = self.groww.get_ltp(
                segment=instrument["segment"],
                exchange_trading_symbols=key,
            )
            return float(ltps.get(key, 0)) or None
        except Exception as e:
            print(f"[{symbol}] ERROR fetching LTP: {e}")
            return None

    # ----------------------------------------------------------
    # Order Execution
    # ----------------------------------------------------------

    def execute_order(self, symbol: str, qty: int, price: float) -> bool:
        """
        Place a market BUY order.

        Args:
            symbol: Trading symbol
            qty:    Number of shares
            price:  Reference price for logging

        Returns:
            True if order placed successfully, False otherwise
        """
        instrument = self.instruments.get(symbol)
        if instrument is None:
            print(f"[{symbol}] ERROR: Cannot place order — instrument not cached.")
            return False

        ref_id = generate_order_reference_id(symbol)
        value  = qty * price

        print(f"[{symbol}] Placing BUY order | Qty={qty} | ~Price=₹{price:.2f} | Value=₹{value:.2f} | Ref={ref_id}")

        try:
            response = self.groww.place_order(
                trading_symbol=instrument["trading_symbol"],
                quantity=qty,
                validity=self.groww.VALIDITY_DAY,
                exchange=instrument["exchange"],
                segment=instrument["segment"],
                product=self.groww.PRODUCT_CNC,
                order_type=self.groww.ORDER_TYPE_MARKET,
                transaction_type=self.groww.TRANSACTION_TYPE_BUY,
            )
            order_id = response.get("groww_order_id", "N/A")
            print(f"[{symbol}] Order placed successfully | ID={order_id}")
            return True

        except Exception as e:
            print(f"[{symbol}] ERROR placing order: {e}")
            return False

    def execute_sell_order(self, symbol: str, qty: int, price: float) -> bool:
        """
            Place a market SELL order to exit a position.

            Args:
                symbol: Trading symbol
                qty:    Number of shares to sell
                price:  Reference price for logging

            Returns:
                True if order placed successfully, False otherwise
            """
        instrument = self.instruments.get(symbol)
        if instrument is None:
            print(f"[{symbol}] ERROR: Cannot place sell order — instrument not cached.")
            return False

        ref_id = generate_order_reference_id(symbol)
        value  = qty * price

        print(f"[{symbol}] Placing SELL order | Qty={qty} | ~Price=₹{price:.2f} | Value=₹{value:.2f} | Ref={ref_id}")

        try:
            response = self.groww.place_order(
                trading_symbol=instrument["trading_symbol"],
                quantity=qty,
                validity=self.groww.VALIDITY_DAY,
                exchange=instrument["exchange"],
                segment=instrument["segment"],
                product=self.groww.PRODUCT_CNC,
                order_type=self.groww.ORDER_TYPE_MARKET,
                transaction_type=self.groww.TRANSACTION_TYPE_SELL,
            )
            order_id = response.get("groww_order_id", "N/A")
            print(f"[{symbol}] Sell order placed successfully | ID={order_id}")
            return True

        except Exception as e:
            print(f"[{symbol}] ERROR placing sell order: {e}")
            return False

    # ----------------------------------------------------------
    # Per-Symbol Processing
    # ----------------------------------------------------------

    def process_symbol(self, symbol: str) -> None:
        """
        Full cycle for one symbol:
          1. Fetch recent candles
          2. Generate signal
          3. Execute order if BUY signal
        """
        df = self.fetch_data(symbol)
        if df.empty:
            print(f"[{symbol}] No data available — skipping.")
            return

        signal = self.generate_signal(symbol, df)

        if signal == "BUY":
            current_price = self.get_current_price(symbol)
            if current_price is None:
                print(f"[{symbol}] Could not fetch LTP — skipping order.")
                return

            qty = calculate_position_size(
                self.capital,
                self.config["position_size_percent"],
                current_price,
            )

            if qty <= 0:
                print(f"[{symbol}] Insufficient capital for order — skipping.")
                return

            success = self.execute_order(symbol, qty, current_price)
            if success:
                self._open_position(symbol, current_price, qty)

    def square_off_all(self) -> None:
        """Close all open positions at market price (end-of-day square-off)."""
        if not self.positions:
            return

        print("\n--- Square-off: Closing all open positions ---")
        for symbol in list(self.positions.keys()):
            pos   = self.positions[symbol]
            price = self.get_current_price(symbol)
            if price is None:
                print(f"[{symbol}] Could not fetch LTP for square-off — skipping.")
                continue
            success = self.execute_sell_order(symbol, pos["qty"], price)
            if success:
                self._close_position(symbol, price, "SQUARE_OFF")

    # ----------------------------------------------------------
    # Main Loop
    # ----------------------------------------------------------

    def run(self) -> None:
        """Autonomous live trading loop — platform manages lifecycle."""
        print("=" * 60)
        print("  DIP BUY STRATEGY — LIVE MODE")
        print(f"  Symbols  : {self.config['symbols']}")
        print(f"  Dip Threshold : {self.config['dip_threshold_percent']}%")
        print(f"  Lookback : {self.config['lookback_minutes']} minutes")
        print(f"  Capital  : ₹{self.config['initial_capital']:,.2f}")
        print("=" * 60)

        while True:
            try:
                current_time = datetime.now()

                # ── Market hours guard ──────────────────────────────
                if not is_market_open(current_time):
                    print(f"Market closed at {current_time.strftime('%H:%M:%S')} — waiting...")
                    time.sleep(60)
                    continue

                # ── End-of-day square-off ───────────────────────────
                from datetime import time as dt_time
                eod_time = datetime.strptime(self.config["trading_end_time"], "%H:%M:%S").time()
                if current_time.time() >= eod_time and self.positions:
                    self.square_off_all()
                    time.sleep(60)
                    continue

                # ── Monitor existing positions ──────────────────────
                if self.positions:
                    current_prices = {}
                    for symbol in list(self.positions.keys()):
                        price = self.get_current_price(symbol)
                        if price:
                            current_prices[symbol] = price
                    self.monitor_positions(current_prices)

                # ── Scan for new signals ────────────────────────────
                if is_within_trading_window(
                    current_time,
                    self.config["trading_start_time"],
                    self.config["trading_end_time"],
                ):
                    print(f"\n--- Cycle at {current_time.strftime('%H:%M:%S')} | "
                          f"Capital=₹{self.capital:,.2f} | "
                          f"Open Positions={len(self.positions)} ---")

                    for symbol in self.config["symbols"]:
                        try:
                            self.process_symbol(symbol)
                        except Exception as e:
                            print(f"[{symbol}] ERROR in process_symbol: {e}")

                else:
                    print(f"Outside trading window at {current_time.strftime('%H:%M:%S')} — waiting...")

                time.sleep(self.config["check_interval_seconds"])

            except Exception as e:
                print(f"ERROR in main loop: {e}")
                time.sleep(60)


# ============================================================
# SECTION 6: MAIN EXECUTION
# ============================================================

def authenticate() -> GrowwAPI:
    """Authenticate and return a GrowwAPI client instance."""
    access_token = GrowwAPI.get_access_token(
        api_key=AUTH_CONFIG["api_key"],
        secret=AUTH_CONFIG["api_secret"],
    )
    return GrowwAPI(access_token)


if __name__ == "__main__":
    groww    = authenticate()
    strategy = LiveStrategy(groww, STRATEGY_CONFIG)
    strategy.run()