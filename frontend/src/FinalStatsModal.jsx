export function FinalStatsModal({ stats, onContinue }) {
  const net = stats.stack - stats.totalBuyIn;

  return (
    <div className="modal-overlay">
      <div className="modal-card">
        <h2>Final stats</h2>
        <div className="modal-row">
          <span>Total buy-in</span>
          <span>{stats.totalBuyIn}</span>
        </div>
        <div className="modal-row">
          <span>Final chip count</span>
          <span>{stats.stack}</span>
        </div>
        <div className="modal-row">
          <span>Net</span>
          <span className={net >= 0 ? "modal-net positive" : "modal-net negative"}>
            {net >= 0 ? "+" : ""}{net}
          </span>
        </div>
        <button className="modal-continue" onClick={onContinue}>Continue</button>
      </div>
    </div>
  );
}
