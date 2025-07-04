package one.mixin.android.web3.details

import android.annotation.SuppressLint
import android.content.ClipData
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import one.mixin.android.Constants
import one.mixin.android.R
import one.mixin.android.api.response.web3.StakeAccount
import one.mixin.android.databinding.FragmentWeb3TransactionsBinding
import one.mixin.android.databinding.ViewWalletWeb3TokenBottomBinding
import one.mixin.android.db.web3.vo.Web3TokenItem
import one.mixin.android.db.web3.vo.Web3TransactionItem
import one.mixin.android.db.web3.vo.isSolToken
import one.mixin.android.db.web3.vo.solLamportToAmount
import one.mixin.android.extension.buildAmountSymbol
import one.mixin.android.extension.colorAttr
import one.mixin.android.extension.colorFromAttribute
import one.mixin.android.extension.dp
import one.mixin.android.extension.getClipboardManager
import one.mixin.android.extension.getParcelableCompat
import one.mixin.android.extension.mainThreadDelayed
import one.mixin.android.extension.navTo
import one.mixin.android.extension.navigate
import one.mixin.android.extension.navigationBarHeight
import one.mixin.android.extension.numberFormat
import one.mixin.android.extension.numberFormat2
import one.mixin.android.extension.openUrl
import one.mixin.android.extension.priceFormat
import one.mixin.android.extension.screenHeight
import one.mixin.android.extension.setQuoteText
import one.mixin.android.extension.statusBarHeight
import one.mixin.android.extension.toast
import one.mixin.android.extension.withArgs
import one.mixin.android.job.MixinJobManager
import one.mixin.android.job.RefreshMarketJob
import one.mixin.android.job.RefreshPriceJob
import one.mixin.android.job.RefreshWeb3TokenJob
import one.mixin.android.tip.Tip
import one.mixin.android.ui.address.TransferDestinationInputFragment
import one.mixin.android.ui.common.BaseFragment
import one.mixin.android.ui.common.PendingTransactionRefreshHelper
import one.mixin.android.ui.home.market.Market
import one.mixin.android.ui.home.web3.StakeAccountSummary
import one.mixin.android.ui.home.web3.Web3ViewModel
import one.mixin.android.ui.home.web3.stake.StakeFragment
import one.mixin.android.ui.home.web3.stake.StakingFragment
import one.mixin.android.ui.home.web3.stake.ValidatorsFragment
import one.mixin.android.ui.home.web3.swap.SwapFragment
import one.mixin.android.ui.wallet.AllWeb3TransactionsFragment
import one.mixin.android.ui.wallet.MarketDetailsFragment.Companion.ARGS_ASSET_ID
import one.mixin.android.ui.wallet.MarketDetailsFragment.Companion.ARGS_MARKET
import one.mixin.android.ui.wallet.adapter.OnSnapshotListener
import one.mixin.android.util.analytics.AnalyticsTracker
import one.mixin.android.util.viewBinding
import one.mixin.android.vo.Fiats
import one.mixin.android.vo.market.MarketItem
import one.mixin.android.web3.ChainType
import one.mixin.android.widget.BottomSheet
import one.mixin.android.widget.DebugClickListener
import java.math.BigDecimal
import javax.inject.Inject

@AndroidEntryPoint
class Web3TransactionsFragment : BaseFragment(R.layout.fragment_web3_transactions), OnSnapshotListener, ViewTreeObserver.OnScrollChangedListener {
    companion object {
        const val TAG = "Web3TransactionsFragment"
        const val ARGS_TOKEN = "args_token"
        const val ARGS_ADDRESS = "args_address"

        fun newInstance(
            address: String,
            web3Token: Web3TokenItem,
        ) =
            Web3TransactionsFragment().withArgs {
                putString(ARGS_ADDRESS, address)
                putParcelable(ARGS_TOKEN, web3Token)
            }
    }

    private val binding by viewBinding(FragmentWeb3TransactionsBinding::bind)
    private val web3ViewModel by viewModels<Web3ViewModel>()

    private var _bottomBinding: ViewWalletWeb3TokenBottomBinding? = null
    private val bottomBinding get() = requireNotNull(_bottomBinding) { "required _bottomBinding is null" }

    @Inject
    lateinit var jobManager: MixinJobManager

    @Inject
    lateinit var tip: Tip

    private val address: String by lazy {
        requireNotNull(requireArguments().getString(ARGS_ADDRESS))
    }

    private val token: Web3TokenItem by lazy {
        requireNotNull(requireArguments().getParcelable<Web3TokenItem>(ARGS_TOKEN) ?: requireArguments().getParcelableCompat(ARGS_TOKEN, Web3TokenItem::class.java))
    }

    private var refreshJob: Job? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        jobManager.addJobInBackground(RefreshPriceJob(token.assetId))
        refreshToken(token.walletId, token.assetId)
        binding.titleView.apply {
            titleTv.setTextOnly(token.name)
            leftIb.setOnClickListener {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
            rightIb.setOnClickListener {
                showBottom()
            }

            binding.apply {
                value.text = try {
                    if (token.priceFiat().toFloat() == 0f) {
                        getString(R.string.NA)
                    } else {
                        "${Fiats.getSymbol()}${token.priceFiat().priceFormat()}"
                    }
                } catch (ignored: NumberFormatException) {
                    "${Fiats.getSymbol()}${token.priceFiat().priceFormat()}"
                }
                web3ViewModel.marketById(token.assetId).observe(viewLifecycleOwner) { market ->
                    if (market != null) {
                        val priceChangePercentage24H = BigDecimal(market.priceChangePercentage24H)
                        val isRising = priceChangePercentage24H >= BigDecimal.ZERO
                        rise.setQuoteText(
                            "${(priceChangePercentage24H).numberFormat2()}%",
                            isRising
                        )
                    } else if (token.priceUsd == "0") {
                        rise.setTextColor(requireContext().colorAttr(R.attr.text_assist))
                        rise.text = "0.00%"
                    } else if (token.changeUsd.isNotEmpty()) {
                        val changeUsd = BigDecimal(token.changeUsd)
                        val isRising = changeUsd >= BigDecimal.ZERO
                        rise.setQuoteText(
                            "${(changeUsd * BigDecimal(100)).numberFormat2()}%",
                            isRising
                        )
                    }
                }
                if (token.isSolToken()) {
                    stake.root.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        getStakeAccounts(address)
                    }
                }
                transactionsRv.listener = this@Web3TransactionsFragment
                bottomCard.post {
                    bottomCard.isVisible = true
                    val remainingHeight =
                        requireContext().screenHeight() - requireContext().statusBarHeight() - requireContext().navigationBarHeight() - titleView.height - topLl.height - marketRl.height - 70.dp
                    bottomRl.updateLayoutParams {
                        height = remainingHeight
                    }

                    if (scrollY > 0) {
                        scrollView.isInvisible = true
                        scrollView.postDelayed(
                            {
                                scrollView.scrollTo(0, scrollY)
                                scrollView.isInvisible = false
                            }, 1
                        )
                    }
                }

                sendReceiveView.send.setOnClickListener {
                    lifecycleScope.launch {
                        val chain = web3ViewModel.web3TokenItemById(token.chainId)
                        if (chain == null) {
                            jobManager.addJobInBackground(RefreshWeb3TokenJob(token.assetId))
                            toast(R.string.Please_wait_a_bit)
                        } else {
                            requireView().navigate(
                                R.id.action_web3_transactions_to_transfer_destination,
                                Bundle().apply {
                                    putString(TransferDestinationInputFragment.ARGS_ADDRESS, address)
                                    putParcelable(TransferDestinationInputFragment.ARGS_WEB3_TOKEN, token)
                                    putParcelable(TransferDestinationInputFragment.ARGS_CHAIN_TOKEN, chain)
                                }
                            )
                        }
                    }
                }
                sendReceiveView.receive.setOnClickListener {
                    requireView().navigate(
                        R.id.action_web3_transactions_to_web3_address,
                        Bundle().apply {
                            putString("address", address)
                        }
                    )
                }
                sendReceiveView.swap.setOnClickListener {
                    AnalyticsTracker.trackSwapStart("web3", "web3")
                    lifecycleScope.launch {
                        val tokens = web3ViewModel.findWeb3TokenItems()
                        requireView().navigate(
                            R.id.action_web3_transactions_to_swap,
                            Bundle().apply {
                                putParcelableArrayList(SwapFragment.ARGS_WEB3_TOKENS, ArrayList(tokens.filter { ((it as Web3TokenItem).balance.toBigDecimalOrNull()?: BigDecimal.ZERO) > BigDecimal.ZERO }))
                                putString(SwapFragment.ARGS_INPUT, token.assetId)
                                putBoolean(SwapFragment.ARGS_IN_MIXIN, false)
                            }
                        )
                    }
                }

                transactionsTitleLl.setOnClickListener {
                    view.navigate(
                        R.id.action_web3_transactions_to_all_web3_transactions,
                        Bundle().apply {
                            putParcelable(AllWeb3TransactionsFragment.ARGS_TOKEN, token)
                        }
                    )
                }
                marketRl.setOnClickListener {
                    lifecycleScope.launch {
                        var market = web3ViewModel.findMarketItemByAssetId(token.assetId)
                        if (market == null) {
                            jobManager.addJobInBackground(RefreshMarketJob(token.assetId))
                            market = MarketItem(
                                "", token.name, token.symbol, token.iconUrl, token.priceUsd,
                                "", "", "", "", "", runCatching {
                                    (BigDecimal(token.priceUsd) * BigDecimal(token.changeUsd)).toPlainString()
                                }.getOrNull() ?: "0", "", token.changeUsd, "", "", "", "", "", "", "", "", "",
                                "", "", "", "", listOf(token.assetId), "", "", null
                            )
                        }
                        view.navigate(
                            R.id.action_web3_transactions_to_market_details,
                            Bundle().apply {
                                putParcelable(ARGS_MARKET, market)
                                putString(ARGS_ASSET_ID, token.assetId)
                            },
                        )
                    }
                }
                marketView.setContent {
                    Market(token.assetId)
                }
            }
        }

        var hasScrolled = false
        val offset = web3ViewModel.scrollOffset

        binding.scrollView.viewTreeObserver.addOnScrollChangedListener(this@Web3TransactionsFragment)
        updateHeader(token)
        lifecycleScope.launch {
            web3ViewModel.web3Transactions(token.assetId).observe(viewLifecycleOwner) { list ->
                binding.transactionsRv.isVisible = list.isNotEmpty()
                binding.bottomRl.isVisible = list.isEmpty()
                binding.transactionsRv.list = list

                if (!hasScrolled && isAdded) {
                    hasScrolled = true
                    binding.scrollView.post {
                        binding.scrollView.scrollTo(0, offset)
                    }
                }
            }
            web3ViewModel.web3TokenExtraFlow(token.assetId).flowOn(Dispatchers.Main).collect { balance ->
                balance?.let {
                    if (isAdded) {
                        updateHeader(token.copy(balance = it))
                    }
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun showBottom() {
        val builder = BottomSheet.Builder(requireActivity())
        _bottomBinding = ViewWalletWeb3TokenBottomBinding.bind(
            View.inflate(
                ContextThemeWrapper(
                    requireActivity(),
                    R.style.Custom
                ), R.layout.view_wallet_web3_token_bottom, null
            )
        )
        builder.setCustomView(bottomBinding.root)
        val bottomSheet = builder.create()
        bottomBinding.apply {
            title.text = token.name
            addressTv.text = token.assetKey
            explorer.setOnClickListener {
                if (token.isSolana()) {
                    context?.openUrl("https://solscan.io/token/" + token.assetKey)
                } else {
                    context?.openUrl("https://etherscan.io/token/" + token.assetKey)
                }
                bottomSheet.dismiss()
            }
            stakeSolTv.isVisible = token.isSolToken()
            stakeSolTv.setOnClickListener {
                this@Web3TransactionsFragment.navTo(
                    ValidatorsFragment.newInstance().apply {
                        setOnSelect { v ->
                            this@Web3TransactionsFragment.navTo(
                                StakeFragment.newInstance(
                                    v,
                                    token.balance
                                ), StakeFragment.TAG
                            )
                        }
                    }, ValidatorsFragment.TAG
                )
                bottomSheet.dismiss()
            }
            copy.setOnClickListener {
                context?.getClipboardManager()
                    ?.setPrimaryClip(ClipData.newPlainText(null, token.assetKey))
                toast(R.string.copied_to_clipboard)
                bottomSheet.dismiss()
            }
            
            hide.setText(if (token.hidden == true) R.string.Show else R.string.Hide)
            hide.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    web3ViewModel.updateTokenHidden(token.assetId, token.walletId, token.hidden != true)
                }
                bottomSheet.dismiss()
                mainThreadDelayed({ activity?.onBackPressedDispatcher?.onBackPressed() }, 200)
            }
            
            cancel.setOnClickListener { bottomSheet.dismiss() }
        }

        bottomSheet.show()
    }


    private fun updateHeader(asset: Web3TokenItem) {
        binding.apply {
            val amountText =
                try {
                    if (asset.balance.toFloat() == 0f) {
                        "0.00"
                    } else {
                        asset.balance.numberFormat()
                    }
                } catch (ignored: NumberFormatException) {
                    asset.balance.numberFormat()
                }
            val color = requireContext().colorFromAttribute(R.attr.text_primary)
            balance.text = buildAmountSymbol(requireContext(), amountText, asset.symbol, color, color)
            balanceAs.text =
                try {
                    if (asset.fiat().toFloat() == 0f) {
                        "≈ ${Fiats.getSymbol()}0.00"
                    } else {
                        "≈ ${Fiats.getSymbol()}${asset.fiat().numberFormat2()}"
                    }
                } catch (ignored: NumberFormatException) {
                    "≈ ${Fiats.getSymbol()}${asset.fiat().numberFormat2()}"
                }
            avatar.loadToken(asset)
            avatar.setOnClickListener(
                object : DebugClickListener() {
                    override fun onDebugClick() {
                    }

                    override fun onSingleClick() {
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshJob = PendingTransactionRefreshHelper.startRefreshData(
            fragment = this,
            web3ViewModel = web3ViewModel,
            jobManager = jobManager,
            refreshJob = refreshJob
        )
    }

    override fun onPause() {
        super.onPause()
        refreshJob = PendingTransactionRefreshHelper.cancelRefreshData(refreshJob)
    }

    private suspend fun getStakeAccounts(address: String) {
        val stakeAccounts = web3ViewModel.getStakeAccounts(address)
        if (!isAdded) { return }
        
        if (stakeAccounts.isNullOrEmpty()) {
            updateStake(StakeAccountSummary(0, "0"))
            return
        }

        var amount: Long = 0
        var count = 0
        stakeAccounts.forEach { a ->
            count++
            amount += (a.account.data.parsed.info.stake.delegation.stake.toLongOrNull() ?: 0)
        }

        val amountStr = amount.solLamportToAmount().stripTrailingZeros().toPlainString()
        
        val stakeAccountSummary = StakeAccountSummary(count, amountStr)
        this.stakeAccounts = stakeAccounts
        
        updateStake(stakeAccountSummary)
    }

    var stakeAccounts: List<StakeAccount>? = null

    private fun updateStake(stakeAccountSummary: StakeAccountSummary?) {
        binding.stake.apply {
            if (stakeAccountSummary == null) {
                iconVa.displayedChild = 0
                amountTv.text = "0 SOL"
                countTv.text = "0 account"
            } else {
                iconVa.displayedChild = 1
                amountTv.text = "${stakeAccountSummary.amount} SOL"
                countTv.text = "${stakeAccountSummary.count} account"
                stakeRl.setOnClickListener {
                    navTo(StakingFragment.newInstance(ArrayList(stakeAccounts ?: emptyList()), token.balance), StakingFragment.TAG)
                }
            }
        }
    }

    private fun refreshToken(walletId: String, assetId: String) {
        jobManager.addJobInBackground(RefreshWeb3TokenJob(null, assetId, address))
    }

    override fun <T> onNormalItemClick(item: T) {
        item as Web3TransactionItem
        findNavController().navigate(
            R.id.action_web3_transactions_to_web3_transaction,
            Bundle().apply {
                putParcelable(Web3TransactionFragment.ARGS_TRANSACTION, item)
                putString(Web3TransactionFragment.ARGS_CHAIN, if (item.chainId == Constants.ChainId.SOLANA_CHAIN_ID) ChainType.solana.name else ChainType.ethereum.name)
                putParcelable(ARGS_TOKEN, token)
            }
        )
    }

    override fun onUserClick(userId: String) {
    }

    override fun onMoreClick() {
        requireView().navigate(
            R.id.action_web3_transactions_to_all_web3_transactions,
            Bundle().apply {
                putParcelable(AllWeb3TransactionsFragment.ARGS_TOKEN, token)
            }
        )
    }

    override fun onScrollChanged() {
        if (isAdded) web3ViewModel.scrollOffset = binding.scrollView.scrollY
    }
}

