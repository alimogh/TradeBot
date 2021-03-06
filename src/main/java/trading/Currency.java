package trading;

import collection.PriceBean;
import collection.PriceReader;
import com.webcerebrium.binance.api.BinanceApiException;
import com.webcerebrium.binance.datatype.BinanceCandlestick;
import com.webcerebrium.binance.datatype.BinanceEventAggTrade;
import com.webcerebrium.binance.datatype.BinanceInterval;
import com.webcerebrium.binance.datatype.BinanceSymbol;
import com.webcerebrium.binance.websocket.BinanceWebSocketAdapterAggTrades;
import indicators.BB;
import indicators.Indicator;
import indicators.MACD;
import indicators.RSI;
import modes.ConfigSetup;

import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Currency {
    private static final String FIAT = "USDT";

    private final String coin;
    private final BinanceSymbol symbol;
    private Trade activeTrade;
    private long candleTime;
    private final List<Indicator> indicators = new ArrayList<>();
    private final AtomicBoolean currentlyCalculating = new AtomicBoolean(false);

    private double currentPrice;
    private long currentTime;

    //Backtesting data
    private final StringBuilder log = new StringBuilder();
    private PriceBean firstBean;


    //Used for SIMULATION and LIVE
    public Currency(String coin) throws BinanceApiException {
        //Every currency is a USDT pair so we only care about the fiat opposite coin
        this.symbol = BinanceSymbol.valueOf(coin + FIAT);
        this.coin = coin;

        //Every currency needs to contain and update our indicators
        List<BinanceCandlestick> history = getCandles(250);//250 gives us functionally the same accuracy as 1000
        List<Double> closingPrices = history.stream().map(candle -> candle.getClose().doubleValue()).collect(Collectors.toList());
        indicators.add(new RSI(closingPrices, 14));
        indicators.add(new MACD(closingPrices, 12, 26, 9));
        indicators.add(new BB(closingPrices, 20));

        //We set the initial values to check against in onMessage based on the latest candle in history
        currentTime = System.currentTimeMillis();
        candleTime = history.get(history.size() - 1).getOpenTime() + 300000L;
        currentPrice = history.get(history.size() - 1).getClose().doubleValue();

        //We add a websocket listener that automatically updates our values and triggers our strategy or trade logic as needed
        CurrentAPI.get().websocketTrades(symbol, new BinanceWebSocketAdapterAggTrades() {
            @Override
            public void onMessage(BinanceEventAggTrade message) {
                //Every message and the resulting indicator and strategy calculations is handled concurrently
                //System.out.println(Thread.currentThread().getId());

                //We want to toss messages that provide no new information
                if (currentPrice == message.getPrice().doubleValue() && !(message.getEventTime() > candleTime)) {
                    return;
                }

                if (message.getEventTime() > candleTime) {
                    accept(new PriceBean(candleTime, currentPrice, true));
                    candleTime += 300000L;
                }

                accept(new PriceBean(message.getEventTime(), message.getPrice().doubleValue()));
            }
        });
        System.out.println("---SETUP DONE FOR " + this);
    }

    //Used for BACKTESTING
    public Currency(String pair, String filePath) throws BinanceApiException {
        this.symbol = BinanceSymbol.valueOf(pair);
        this.coin = pair.replace("USDT", "");
        try (PriceReader reader = new PriceReader(filePath)) {
            PriceBean bean = reader.readPrice();

            firstBean = bean;
            long start = bean.getTimestamp();

            List<BinanceCandlestick> history = getCandles(1000, start - 86400000L, start + 300000);
            List<Double> closingPrices = IntStream.range(history.size() - 251, history.size() - 1).mapToObj(history::get).map(candle -> candle.getClose().doubleValue()).collect(Collectors.toList());
            indicators.add(new RSI(closingPrices, 14));
            indicators.add(new MACD(closingPrices, 12, 26, 9));
            indicators.add(new BB(closingPrices, 20));

            while (bean != null) {
                accept(bean);
                bean = reader.readPrice();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void accept(PriceBean bean) {
        //Make sure we dont get concurrency issues
        if (currentlyCalculating.get()) {
            System.out.println("------------WARNING, NEW THREAD STARTED ON " + coin + " MESSAGE DURING UNFINISHED PREVIOUS MESSAGE CALCULATIONS");
        }
        currentlyCalculating.set(true);

        currentPrice = bean.getPrice();
        currentTime = bean.getTimestamp();

        if (bean.isClosing()) {
            indicators.forEach(indicator -> indicator.update(bean.getPrice()));
            /*RSI rsi = (RSI) indicators.get(0);
            try {
                Database.insertIndicatorValue("rsiAvgUp",symbol.toString(), rsi.getAvgUp(), bean.getTimestamp());
                Database.insertIndicatorValue("rsiAvgDwn",symbol.toString(), rsi.getAvgDwn(), bean.getTimestamp());
            } catch (SQLException throwables) {
                throwables.printStackTrace();
            }*/
            if (Mode.get().equals(Mode.BACKTESTING)) {
                appendLogLine(Formatter.formatDate(currentTime) + "  " + toString());
            }
        }


        //We can disable the strategy and trading logic to only check indicator and price accuracy
        int confluence = check();
        if (hasActiveTrade()) { //We only allow one active trade per currency, this means we only need to do one of the following:
            activeTrade.update(currentPrice, confluence);//Update the active trade stop-loss and high values
        } else {
            if (confluence >= 2) {
                StringJoiner joiner = new StringJoiner("", "Trade opened due to: ", "");
                for (Indicator indicator : indicators) {
                    String explanation = indicator.getExplanation();
                    joiner.add(explanation.equals("") ? "" : explanation + "\t");
                }
                BuySell.open(Currency.this, joiner.toString(), bean.getTimestamp());
            }
        }

        currentlyCalculating.set(false);
    }

    public int check() {
        return indicators.stream().mapToInt(indicator -> indicator.check(currentPrice)).sum();
    }

    public List<BinanceCandlestick> getCandles(int length) throws BinanceApiException {
        return (CurrentAPI.get()).klines(symbol, BinanceInterval.FIVE_MIN, length, null);
    }

    public List<BinanceCandlestick> getCandles(int length, long start, long end) throws BinanceApiException {
        Map<String, Long> options = new HashMap<>();
        options.put("startTime", start);
        options.put("endTime", end);
        return (CurrentAPI.get()).klines(symbol, BinanceInterval.FIVE_MIN, length, options);
    }

    public BinanceSymbol getSymbol() {
        return symbol;
    }

    public String getCoin() {
        return coin;
    }

    public double getPrice() {
        return currentPrice;
    }

    public long getCurrentTime() {
        return currentTime;
    }

    public boolean hasActiveTrade() {
        return activeTrade != null;
    }

    public void setActiveTrade(Trade activeTrade) {
        this.activeTrade = activeTrade;
    }

    public void appendLogLine(String s) {
        log.append(s).append("\n");
    }

    public void log(String path) {
        List<Trade> tradeHistory = new ArrayList<>(BuySell.getAccount().getTradeHistory());
        tradeHistory.sort(Comparator.comparingDouble(Trade::getProfit));
        double maxLoss = tradeHistory.get(0).getProfit();
        double maxGain = tradeHistory.get(tradeHistory.size() - 1).getProfit();
        int lossTrades = 0;
        double lossSum = 0;
        int gainTrades = 0;
        double gainSum = 0;
        long tradeDurs = 0;
        for (Trade trade : tradeHistory) {
            double profit = trade.getProfit();
            if (profit < 0) {
                lossTrades += 1;
                lossSum += profit;
            } else if (profit > 0) {
                gainTrades += 1;
                gainSum += profit;
            }
            tradeDurs += trade.getDuration();
        }

        double tradePerWeek = 604800000.0 / (double) ((currentTime - firstBean.getTimestamp()) / tradeHistory.size());

        try (FileWriter writer = new FileWriter(path)) {
            writer.write("Test ended " + Formatter.formatDate(LocalDateTime.now()) + " \n");
            writer.write("\nMarket performance: " + Formatter.formatPercent((currentPrice - firstBean.getPrice()) / firstBean.getPrice()));
            writer.write("\nBot performance: " + Formatter.formatPercent(BuySell.getAccount().getProfit()) + "\n\n");
            writer.write(BuySell.getAccount().getTradeHistory().size() + " closed trades"
                    + " (" + Formatter.formatDecimal(tradePerWeek) + " trades per week) with an average holding length of "
                    + Formatter.formatDuration(Duration.of(tradeDurs / tradeHistory.size(), ChronoUnit.MILLIS)) + " hours");
            writer.write("\nLoss trades:\n");
            writer.write(lossTrades + " trades, " + Formatter.formatPercent(lossSum / (double) lossTrades) + " average, " + Formatter.formatPercent(maxLoss) + " max");
            writer.write("\nProfitable trades:\n");
            writer.write(gainTrades + " trades, " + Formatter.formatPercent(gainSum / (double) gainTrades) + " average, " + Formatter.formatPercent(maxGain) + " max");
            writer.write("\n\nClosed trades (least to most profitable):\n");
            for (Trade trade : tradeHistory) {
                writer.write(trade.toString() + "\n");
            }
            writer.write("\n\nCONFIG:\n");
            writer.write(ConfigSetup.getSetup());
            writer.write("\n\nFULL LOG:\n\n");
            writer.write(log.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("---Log file generated at " + path);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder(coin + " price: " + currentPrice);
        if (currentTime == candleTime)
            indicators.forEach(indicator -> s.append(", ").append(indicator.getClass().getSimpleName()).append(": ").append(Formatter.formatDecimal(indicator.get())));
        else
            indicators.forEach(indicator -> s.append(", ").append(indicator.getClass().getSimpleName()).append(": ").append(Formatter.formatDecimal(indicator.getTemp(currentPrice))));
        s.append(", hasActive: ").append(hasActiveTrade()).append(")");
        return s.toString();
    }

    @Override
    public int hashCode() {
        return coin.hashCode();
    }
}
